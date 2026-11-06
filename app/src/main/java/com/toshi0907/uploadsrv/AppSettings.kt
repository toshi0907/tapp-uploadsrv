package com.toshi0907.uploadsrv

import android.content.Context
import android.content.Intent
import android.net.Uri
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
        private const val PREFS_NAME = "settings"
        private const val KEY_SAVE_FOLDER_URI = "save_folder_uri"
        private const val KEY_PORT = "port"
        private const val KEY_LAST_NOTIFIED_UPDATE_BUILD = "last_notified_update_build"

        /** 一般アプリが待ち受けできる範囲(1024〜65535)か。 */
        fun isValidPort(port: Int): Boolean = port in 1024..65535
    }
}
