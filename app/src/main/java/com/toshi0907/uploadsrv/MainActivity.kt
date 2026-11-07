package com.toshi0907.uploadsrv

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(Modifier.padding(innerPadding))
                }
            }
        }
        UpdateCheckWorker.schedule(this)
        // 画面の再生成(回転など)では確認し直さない
        if (savedInstanceState == null && !handleUpdateIntent(intent)) {
            UpdateManager.checkAsync(this)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUpdateIntent(intent)
    }

    /** 更新通知のタップで開かれた場合はダウンロードを始める。処理したら true。 */
    private fun handleUpdateIntent(intent: Intent?): Boolean {
        if (intent?.action != UpdateManager.ACTION_START_UPDATE) return false
        // 履歴(最近のアプリ)から再表示されたときに再実行しない
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return false
        setIntent(Intent(intent).setAction(Intent.ACTION_MAIN))
        UpdateManager.startUpdate(this)
        return true
    }
}

/** 保存先フォルダの表示用の状態。 */
private data class FolderState(val selected: Boolean, val name: String?, val valid: Boolean)

private fun AppSettings.folderState(): FolderState =
    FolderState(
        selected = saveFolderUri != null,
        name = saveFolder()?.name,
        valid = hasValidSaveFolder(),
    )

@Composable
private fun MainScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var folder by remember { mutableStateOf(settings.folderState()) }
    var saveMode by remember { mutableStateOf(settings.saveMode) }
    var albumText by remember { mutableStateOf(settings.albumName) }

    // 設定アプリ等で権限が外された場合に備え、画面復帰のたびに再確認する
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        folder = settings.folderState()
    }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { settings.updateSaveFolder(uri) }
            .onFailure {
                Toast.makeText(context, "フォルダの権限を取得できませんでした", Toast.LENGTH_LONG).show()
            }
        folder = settings.folderState()
    }

    val albumValid = AppSettings.isValidAlbumName(albumText)
    val destinationValid = when (saveMode) {
        SaveMode.GALLERY -> albumValid
        SaveMode.FOLDER -> folder.valid
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "保存先", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "同じ名前のファイルがある場合は「名前 (1).jpg」のような別名で保存します。" +
                        "変更は次のアップロードから反映されます",
                    style = MaterialTheme.typography.bodySmall,
                )

                val gallerySupported = AppSettings.isGallerySupported()
                SaveModeOption(
                    label = "ギャラリー(アルバム)",
                    selected = saveMode == SaveMode.GALLERY,
                    enabled = gallerySupported,
                    onSelect = {
                        settings.saveMode = SaveMode.GALLERY
                        saveMode = settings.saveMode
                    },
                )
                if (!gallerySupported) {
                    Text(
                        text = "ギャラリーへの保存は Android 10 以上で利用できます",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (saveMode == SaveMode.GALLERY) {
                    OutlinedTextField(
                        value = albumText,
                        onValueChange = { text ->
                            albumText = text
                            if (AppSettings.isValidAlbumName(text)) settings.albumName = text
                        },
                        label = { Text("アルバム名") },
                        singleLine = true,
                        isError = !albumValid,
                        supportingText = {
                            Text(
                                if (albumValid) {
                                    "Pictures/$albumText に保存し、ギャラリーにアルバムとして表示されます。" +
                                        "写真・動画以外のファイルは保存しません"
                                } else {
                                    "1〜${AppSettings.MAX_ALBUM_NAME_LENGTH}文字で入力してください" +
                                        "(前後の空白、先頭の「.」、記号 / \\ : * ? \" < > | は使えません)"
                                },
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                SaveModeOption(
                    label = "任意のフォルダ",
                    selected = saveMode == SaveMode.FOLDER,
                    enabled = true,
                    onSelect = {
                        settings.saveMode = SaveMode.FOLDER
                        saveMode = settings.saveMode
                    },
                )
                if (saveMode == SaveMode.FOLDER) {
                    val description = when {
                        !folder.selected -> "未選択"
                        !folder.valid -> "${folder.name ?: "(不明)"}(アクセス権限がありません。選び直してください)"
                        else -> folder.name ?: "(名前を取得できません)"
                    }
                    Text(
                        text = description,
                        color = if (folder.valid) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    Button(onClick = { pickFolder.launch(null) }) {
                        Text(text = if (folder.valid) "フォルダを変更" else "フォルダを選択")
                    }
                }
            }
        }

        ServerCard(settings = settings, destinationValid = destinationValid)

        AccessLogCard()

        BatteryOptimizationCard()

        UpdateCard()
    }
}

/** 保存方式の選択肢(ラジオボタン+ラベル)。行全体をタップで選べる。 */
@Composable
private fun SaveModeOption(label: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun UpdateCard() {
    val context = LocalContext.current
    val state by UpdateManager.state.collectAsState()
    var canInstall by remember { mutableStateOf(UpdateManager.canInstallPackages(context)) }
    // 設定画面で許可して戻ってきたときに反映する
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canInstall = UpdateManager.canInstallPackages(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "アプリの更新", style = MaterialTheme.typography.titleMedium)
            Text(text = "現在のビルド: #${UpdateManager.currentBuild}")

            when (val s = state) {
                UpdateState.Idle -> Text(text = "6時間ごとに自動で確認します")
                UpdateState.Checking -> Text(text = "確認中…")
                UpdateState.UpToDate -> Text(text = "最新です")
                is UpdateState.Available -> Text(
                    text = "新しいビルド #${s.release.buildNumber} があります",
                    color = MaterialTheme.colorScheme.primary,
                )
                is UpdateState.Downloading -> {
                    val percent = s.progress?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                    Text(text = "ビルド #${s.release.buildNumber} をダウンロード中…$percent")
                    val progress = s.progress
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
                is UpdateState.ReadyToInstall -> Text(
                    text = "ビルド #${s.release.buildNumber} のダウンロードが完了しました",
                    color = MaterialTheme.colorScheme.primary,
                )
                is UpdateState.Failed -> Text(text = s.message, color = MaterialTheme.colorScheme.error)
            }

            val hasUpdate = state is UpdateState.Available || state is UpdateState.ReadyToInstall
            if (hasUpdate && !canInstall) {
                Text(
                    text = "インストールには、このアプリに「不明なアプリのインストール」の許可が必要です",
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = { UpdateManager.openInstallPermissionSettings(context) }) {
                    Text(text = "許可の設定を開く")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (state) {
                    is UpdateState.Available -> Button(onClick = { UpdateManager.startUpdate(context) }) {
                        Text(text = "ダウンロードして更新")
                    }
                    is UpdateState.ReadyToInstall -> Button(onClick = { UpdateManager.install(context) }) {
                        Text(text = "インストール")
                    }
                    else -> Unit
                }
                OutlinedButton(
                    onClick = { UpdateManager.checkAsync(context) },
                    enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                ) {
                    Text(text = "今すぐ確認")
                }
            }
        }
    }
}

@Composable
private fun ServerCard(settings: AppSettings, destinationValid: Boolean) {
    val context = LocalContext.current
    val requestNotification = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { UploadService.start(context) }
    val state by ServerController.state.collectAsState()
    var portText by remember { mutableStateOf(settings.port.toString()) }
    val portValue = portText.toIntOrNull()?.takeIf(AppSettings::isValidPort)
    val running = state is ServerState.Running

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "サーバー",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = running,
                    enabled = running || (destinationValid && portValue != null),
                    onCheckedChange = { on ->
                        if (on && portValue != null) {
                            settings.port = portValue
                            settings.serverEnabled = true
                            // 通知を出せなくてもサービスは動くため、許可の結果に関わらず起動する
                            if (needsNotificationPermission(context)) {
                                requestNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                UploadService.start(context)
                            }
                        } else {
                            UploadService.stop(context)
                        }
                    },
                )
            }

            OutlinedTextField(
                value = portText,
                onValueChange = { text -> portText = text.filter(Char::isDigit).take(5) },
                label = { Text("ポート番号") },
                singleLine = true,
                enabled = !running,
                isError = portValue == null,
                supportingText = {
                    Text(if (portValue == null) "1024〜65535 を入力してください" else "初期値は ${AppSettings.DEFAULT_PORT}")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            when (val s = state) {
                is ServerState.Running -> {
                    var addresses by remember(s) { mutableStateOf(NetworkUtils.localIpv4Addresses()) }
                    // Wi-Fiの切り替え等でIPが変わることがあるため、画面復帰のたびに取り直す
                    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                        addresses = NetworkUtils.localIpv4Addresses()
                    }
                    Text(text = "接続URL(同じWi-Fiのブラウザで開く)")
                    if (addresses.isEmpty()) {
                        Text(
                            text = "IPアドレスを取得できません。Wi-Fi接続を確認してください",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val urls = addresses.map { ip -> "http://$ip:${s.port}" }
                        SelectionContainer {
                            Column {
                                urls.forEach { url ->
                                    Text(
                                        text = url,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                        // 先頭(Wi-Fi優先)のURLだけをQRコードにする。モバイル回線等のアドレスは同じWi-Fiから届かないため
                        Text(text = "カメラで読み取ると ${urls.first()} を開けます")
                        QrCode(
                            text = urls.first(),
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        )
                        if (s.https) {
                            Text(text = "HTTPSでも接続できます(自己署名証明書のため、ブラウザに警告が表示されます)")
                            SelectionContainer {
                                Text(
                                    text = addresses.joinToString("\n") { ip -> "https://$ip:${s.port}" },
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        val alternatives = if (s.https) "別のブラウザ(Brave等)や https:// のURL" else "別のブラウザ(Brave等)"
                        Text(
                            text = "開けない場合は、${alternatives}を試してください。" +
                                "下の「接続ログ」で、通信が届いているかを確認できます",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                is ServerState.Failed -> Text(text = s.message, color = MaterialTheme.colorScheme.error)
                ServerState.Stopped -> Text(
                    text = if (destinationValid) "停止中" else "保存先を設定すると起動できます",
                )
            }
        }
    }
}

/** 接続ログ(診断用)。新しい順に表示する。 */
@Composable
private fun AccessLogCard() {
    val entries by AccessLog.entries.collectAsState()
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.JAPAN) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "接続ログ",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = AccessLog::clear, enabled = entries.isNotEmpty()) {
                    Text(text = "クリア")
                }
            }
            if (entries.isEmpty()) {
                Text(text = "まだ接続はありません。別の端末からアクセスすると、ここに記録されます")
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        entries.forEach { entry ->
                            Text(
                                text = "${timeFormat.format(Date(entry.time))} ${entry.client}\n${entry.message}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 電池最適化の除外を案内するカード(任意)。
 * 最適化対象のままだと Doze 中にネットワークが止まり、アップロードを受けられないことがある。
 */
@Composable
private fun BatteryOptimizationCard() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // 設定画面から戻ったときに状態を反映する
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ignoring = isIgnoringBatteryOptimizations(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "電池の最適化", style = MaterialTheme.typography.titleMedium)
            if (ignoring) {
                Text(text = "除外済みです(バックグラウンドでも受信を続けやすくなります)")
            } else {
                Text(
                    text = "画面オフ中も安定して受信するには、電池の最適化からこのアプリを除外してください(任意)",
                )
                Button(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                    Text(text = "最適化を除外する")
                }
            }
        }
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true

@SuppressLint("BatteryLife")
private fun requestIgnoreBatteryOptimizations(context: Context) {
    val request = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}"),
    )
    try {
        context.startActivity(request)
    } catch (e: ActivityNotFoundException) {
        // 確認ダイアログを持たない端末では一覧画面を開く
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            .onFailure {
                Toast.makeText(context, "電池の最適化の設定画面を開けませんでした", Toast.LENGTH_LONG).show()
            }
    }
}

/** Android 13 以降で通知の許可がまだ無いか。 */
private fun needsNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
