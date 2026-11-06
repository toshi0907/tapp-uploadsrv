package com.toshi0907.uploadsrv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GitHub Release(タグ latest)の情報。 */
data class ReleaseInfo(val buildNumber: Int, val apkUrl: String, val apkSize: Long)

/** アプリ内アップデートの状態。 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState

    /** [progress] は 0〜1。全体サイズが不明なら null。 */
    data class Downloading(val release: ReleaseInfo, val progress: Float?) : UpdateState
    data class ReadyToInstall(val release: ReleaseInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * GitHub Release の latest に埋め込まれたビルド番号(CIの実行番号)と、
 * 自身のビルド番号(versionCode)を比較してアプリ内アップデートを行う。
 * 確認・ダウンロードは複数箇所(画面・WorkManager)から呼ばれうるため Mutex で直列化する。
 */
object UpdateManager {
    const val ACTION_START_UPDATE = "com.toshi0907.uploadsrv.action.START_UPDATE"
    private const val RELEASE_API =
        "https://api.github.com/repos/toshi0907/tapp-uploadsrv/releases/tags/latest"
    private const val USER_AGENT = "tapp-uploadsrv-updater"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val BUILD_IN_BODY = Regex("""build:\s*(\d+)""")
    private val BUILD_IN_APK_NAME = Regex("""(\d+)\.apk$""")

    val currentBuild: Int = BuildConfig.VERSION_CODE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var updateJob: Job? = null
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun checkAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { check(app) }
    }

    /**
     * 最新リリースを確認し、自身より新しいビルドがあればその情報を返す。
     * 確認に失敗した場合は null。
     */
    suspend fun check(context: Context): ReleaseInfo? = mutex.withLock {
        val before = _state.value
        if (before !is UpdateState.ReadyToInstall) {
            _state.value = UpdateState.Checking
            // インストール待ちでなければ、過去にダウンロードしたAPKは不要
            withContext(Dispatchers.IO) { updatesDir(context).deleteRecursively() }
        }
        try {
            val latest = fetchLatest()
            _state.value = when {
                latest.buildNumber <= currentBuild -> UpdateState.UpToDate
                before is UpdateState.ReadyToInstall &&
                    before.release.buildNumber == latest.buildNumber -> before
                else -> UpdateState.Available(latest)
            }
            latest.takeIf { it.buildNumber > currentBuild }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = if (before is UpdateState.ReadyToInstall) {
                before
            } else {
                UpdateState.Failed("更新を確認できませんでした: ${e.message}")
            }
            null
        }
    }

    /** 最新ビルドをダウンロードし、完了したらインストーラを起動する。 */
    fun startUpdate(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (updateJob?.isActive == true) return
            updateJob = scope.launch {
                val file = mutex.withLock { downloadLatestLocked(app) } ?: return@launch
                withContext(Dispatchers.Main) { launchInstaller(app, file) }
            }
        }
    }

    /** ダウンロード済みの更新APKのインストーラを起動する。 */
    fun install(context: Context) {
        val s = _state.value as? UpdateState.ReadyToInstall ?: return
        launchInstaller(context.applicationContext, s.file)
    }

    /** 「不明なアプリのインストール」が許可されているか。 */
    fun canInstallPackages(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    private suspend fun downloadLatestLocked(context: Context): File? {
        val before = _state.value
        if (before is UpdateState.ReadyToInstall && before.file.exists()) return before.file
        return try {
            _state.value = UpdateState.Checking
            val release = fetchLatest()
            if (release.buildNumber <= currentBuild) {
                _state.value = UpdateState.UpToDate
                return null
            }
            _state.value = UpdateState.Downloading(release, null)
            val file = download(context, release) { progress ->
                _state.value = UpdateState.Downloading(release, progress)
            }
            verify(context, file)
            _state.value = UpdateState.ReadyToInstall(release, file)
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("更新のダウンロードに失敗しました: ${e.message}")
            null
        }
    }

    private fun launchInstaller(context: Context, file: File) {
        if (!canInstallPackages(context)) {
            // 許可後に画面の「インストール」ボタンから再開する
            Toast.makeText(context, "「不明なアプリのインストール」を許可してください", Toast.LENGTH_LONG).show()
            openInstallPermissionSettings(context)
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, "インストーラを起動できませんでした", Toast.LENGTH_LONG).show()
        }
    }

    private suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseRelease(JSONObject(body))
        } finally {
            conn.disconnect()
        }
    }

    /** Release本文の "build: N"(無ければAPK名末尾の番号)をビルド番号として読む。 */
    private fun parseRelease(json: JSONObject): ReleaseInfo {
        val assets = json.optJSONArray("assets") ?: throw IOException("APKが見つかりません")
        val apk = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") }
            ?: throw IOException("APKが見つかりません")
        val build = BUILD_IN_BODY.find(json.optString("body"))?.groupValues?.get(1)?.toIntOrNull()
            ?: BUILD_IN_APK_NAME.find(apk.optString("name"))?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("ビルド番号を取得できません")
        return ReleaseInfo(
            buildNumber = build,
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size", -1L),
        )
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    private suspend fun download(
        context: Context,
        release: ReleaseInfo,
        onProgress: (Float?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val dir = updatesDir(context).apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        // browser_download_url は別ホストへリダイレクトされる(https同士なので自動追従される)
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val total = conn.contentLengthLong.takeIf { it > 0 }
                ?: release.apkSize.takeIf { it > 0 }
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                part.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total != null) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            if (total != null && done != total) throw IOException("ダウンロードが途中で終了しました")
            target.delete()
            if (!part.renameTo(target)) throw IOException("ファイルを保存できません")
            target
        } finally {
            conn.disconnect()
            part.delete()
        }
    }

    /** ダウンロードしたAPKが自分自身の、より新しいビルドであることを確認する。 */
    private suspend fun verify(context: Context, file: File) = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.path, 0)
            ?: throw IOException("APKを解析できません")
        if (info.packageName != context.packageName) throw IOException("別のアプリのAPKです")
        if (PackageInfoCompat.getLongVersionCode(info) <= currentBuild) {
            throw IOException("現在より新しいビルドではありません")
        }
    }
}
