package com.toshi0907.uploadsrv

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile

/**
 * アプリ設定(SharedPreferences)。保存先フォルダのツリーURIなどを保持する。
 */
class AppSettings(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 保存先フォルダのツリーURI。未選択なら null。 */
    var saveFolderUri: Uri?
        get() = prefs.getString(KEY_SAVE_FOLDER_URI, null)?.let(Uri::parse)
        private set(value) = prefs.edit { putString(KEY_SAVE_FOLDER_URI, value?.toString()) }

    /**
     * 保存方式。ギャラリー方式は Android 10 未満では使えないため、その場合は常に任意フォルダ方式になる。
     */
    var saveMode: SaveMode
        get() {
            val stored = prefs.getString(KEY_SAVE_MODE, null)
                ?.let { name -> SaveMode.entries.firstOrNull { it.name == name } }
                ?: SaveMode.FOLDER
            return if (stored == SaveMode.GALLERY && !isGallerySupported()) SaveMode.FOLDER else stored
        }
        set(value) = prefs.edit { putString(KEY_SAVE_MODE, value.name) }

    /** ギャラリー方式で保存するアルバム名(`Pictures/<アルバム名>`)。 */
    var albumName: String
        get() = prefs.getString(KEY_ALBUM_NAME, null) ?: DEFAULT_ALBUM_NAME
        set(value) {
            require(isValidAlbumName(value)) { "invalid album name: $value" }
            prefs.edit { putString(KEY_ALBUM_NAME, value) }
        }

    /** HTTPサーバーの待ち受けポート。 */
    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) {
            require(isValidPort(value)) { "invalid port: $value" }
            prefs.edit { putInt(KEY_PORT, value) }
        }

    /** 更新通知を出した最後のビルド番号(同じビルドで何度も通知しないため)。 */
    var lastNotifiedUpdateBuild: Int
        get() = prefs.getInt(KEY_LAST_NOTIFIED_UPDATE_BUILD, 0)
        set(value) = prefs.edit { putInt(KEY_LAST_NOTIFIED_UPDATE_BUILD, value) }

    /**
     * ユーザーがサーバーをONにしているか。端末起動時の自動起動の判定に使う。
     * 画面のスイッチでONにしたとき true、スイッチや通知の「停止」で止めたとき false にする。
     */
    var serverEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVER_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_SERVER_ENABLED, value) }

    /**
     * SAFで選択されたフォルダを保存先として登録する。
     * 権限を永続化し、以前のフォルダの永続権限は解放する。
     */
    fun updateSaveFolder(uri: Uri) {
        val resolver = appContext.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        resolver.takePersistableUriPermission(uri, flags)
        val previous = saveFolderUri
        saveFolderUri = uri
        if (previous != null && previous != uri) {
            runCatching { resolver.releasePersistableUriPermission(previous, flags) }
        }
    }

    /** 現在の保存方式で保存できる状態か(サーバーを起動できるか)。 */
    fun hasValidDestination(): Boolean = when (saveMode) {
        SaveMode.GALLERY -> isValidAlbumName(albumName)
        SaveMode.FOLDER -> hasValidSaveFolder()
    }

    /** 保存先フォルダの書き込み権限が現在も有効か。 */
    fun hasValidSaveFolder(): Boolean {
        val uri = saveFolderUri ?: return false
        val granted = appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        return granted && saveFolder()?.canWrite() == true
    }

    /** 保存先フォルダの DocumentFile。未選択なら null。 */
    fun saveFolder(): DocumentFile? =
        saveFolderUri?.let { DocumentFile.fromTreeUri(appContext, it) }

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_ALBUM_NAME = "アップロード"
        const val MAX_ALBUM_NAME_LENGTH = 100
        private const val PREFS_NAME = "settings"
        private const val KEY_SAVE_FOLDER_URI = "save_folder_uri"
        private const val KEY_PORT = "port"
        private const val KEY_SAVE_MODE = "save_mode"
        private const val KEY_ALBUM_NAME = "album_name"
        private const val KEY_SERVER_ENABLED = "server_enabled"
        private const val KEY_LAST_NOTIFIED_UPDATE_BUILD = "last_notified_update_build"

        /** 一般アプリが待ち受けできる範囲(1024〜65535)か。 */
        fun isValidPort(port: Int): Boolean = port in 1024..65535

        /** ギャラリー方式(MediaStore の RELATIVE_PATH を使う)を使えるか。Android 10 以降のみ。 */
        fun isGallerySupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        /** アルバム名(フォルダ名)として使えない文字。パス区切りのほか、FAT系で使えない記号も除く。 */
        private const val INVALID_ALBUM_CHARS = "/\\:*?\"<>|"

        /**
         * アルバム名として使えるか。空・前後の空白・`.` で始まる名前(隠しフォルダ扱い)・
         * パス区切り等の記号・制御文字・長すぎる名前は不可。
         */
        fun isValidAlbumName(name: String): Boolean =
            name.isNotEmpty() &&
                name.length <= MAX_ALBUM_NAME_LENGTH &&
                name == name.trim() &&
                !name.startsWith('.') &&
                name.none { it.isISOControl() || it in INVALID_ALBUM_CHARS }
    }
}

/** アップロードの保存方式。 */
enum class SaveMode {
    /** SAFで選んだ任意のフォルダに保存する。 */
    FOLDER,

    /** MediaStore 経由で `Pictures/<アルバム名>` に保存し、ギャラリーのアルバムとして表示させる。 */
    GALLERY,
}
