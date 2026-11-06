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

    private companion object {
        const val PREFS_NAME = "settings"
        const val KEY_SAVE_FOLDER_URI = "save_folder_uri"
    }
}
