package com.toshi0907.uploadsrv

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/** 保存できない種類のファイル(ギャラリー方式での写真・動画以外)。 */
class UnsupportedFileException(message: String) : IOException(message)

/**
 * アップロードされたファイルの保存先。同名ファイルがあれば上書きせず、`name (1).ext` のような別名で保存する。
 */
interface UploadStorage {
    /** [name] を元にした名前で保存し、実際に保存した名前を返す。 */
    suspend fun save(name: String, write: suspend (OutputStream) -> Unit): String

    companion object {
        /** 現在の設定に応じた保存先を返す。保存できない状態なら [IOException]。 */
        fun from(context: Context, settings: AppSettings): UploadStorage = when (settings.saveMode) {
            SaveMode.GALLERY -> {
                // isGallerySupported() と同じ条件。lint が API レベルの確認を認識できるよう直接比較する
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw IOException("この端末ではギャラリーに保存できません")
                val album = settings.albumName
                if (!AppSettings.isValidAlbumName(album)) throw IOException("アルバム名が不正です")
                GalleryStorage(context.applicationContext, album)
            }
            SaveMode.FOLDER -> {
                if (!settings.hasValidSaveFolder()) {
                    throw IOException("保存先フォルダが未設定、またはアクセス権限がありません")
                }
                val folder = settings.saveFolder() ?: throw IOException("保存先フォルダを開けません")
                FolderStorage(context.applicationContext, folder)
            }
        }

        /**
         * [taken] に含まれない名前を返す。重複する場合は拡張子の前に ` (1)`, ` (2)`, ... を付ける。
         * 比較は大文字小文字を区別しない(多くのストレージで区別されないため)。
         */
        fun uniqueName(name: String, taken: Set<String>): String {
            val lower = taken.mapTo(HashSet()) { it.lowercase() }
            if (name.lowercase() !in lower) return name
            val dot = name.lastIndexOf('.')
            // 先頭のドットだけの名前(.hidden 等)は拡張子とみなさない
            val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
            for (i in 1..MAX_SUFFIX) {
                val candidate = "$base ($i)$ext"
                if (candidate.lowercase() !in lower) return candidate
            }
            throw IOException("別名を決められません: $name")
        }

        private const val MAX_SUFFIX = 9999
    }
}

/** SAFで選んだフォルダに保存する。 */
private class FolderStorage(private val context: Context, private val folder: DocumentFile) : UploadStorage {
    override suspend fun save(name: String, write: suspend (OutputStream) -> Unit): String {
        val file = withContext(Dispatchers.IO) {
            val taken = resolverCall { folder.listFiles().mapNotNullTo(HashSet()) { it.name } }
            val target = UploadStorage.uniqueName(name, taken)
            // MIMEを octet-stream にして、プロバイダに拡張子を付け替えられないようにする。
            // 同時に同名が作られた場合でもプロバイダが別名を付けるため、実際の名前は作成結果から取る
            val created = resolverCall { folder.createFile("application/octet-stream", target) }
            created ?: throw IOException("ファイルを作成できません: $name")
        }
        try {
            writeTo(context.contentResolver, file.uri, name, write)
        } catch (e: Throwable) {
            // 書きかけのファイルを残さない
            withContext(NonCancellable + Dispatchers.IO) { runCatching { file.delete() } }
            throw e
        }
        return withContext(Dispatchers.IO) { file.name } ?: name
    }
}

/**
 * MediaStore 経由で `Pictures/<アルバム名>/` に保存する(Android 10 以降)。
 * ギャラリーアプリにはフォルダ単位のアルバムとして表示される。写真・動画以外は保存しない。
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class GalleryStorage(private val context: Context, album: String) : UploadStorage {
    private val relativePath = "${Environment.DIRECTORY_PICTURES}/$album/"

    override suspend fun save(name: String, write: suspend (OutputStream) -> Unit): String {
        val mime = mimeTypeOf(name)
        val collection = when {
            mime == null -> null
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> null
        } ?: throw UnsupportedFileException("写真・動画以外のため保存しませんでした")

        val resolver = context.contentResolver
        val item = withContext(Dispatchers.IO) {
            // 権限なしで見えるのはこのアプリが保存したファイルだけ。他のアプリのファイルと重なった場合は
            // MediaStore が別名を付けるため、実際の名前は保存後に読み直す
            val target = UploadStorage.uniqueName(name, resolverCall { existingNames(resolver) })
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, target)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                // 書き込み中はギャラリーに出さない
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val inserted = resolverCall { resolver.insert(collection, values) }
            inserted ?: throw IOException("ギャラリーに登録できません: $name")
        }
        try {
            writeTo(resolver, item, name, write)
            withContext(Dispatchers.IO) {
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolverCall { resolver.update(item, published, null, null) }
            }
        } catch (e: Throwable) {
            // 書きかけの項目を残さない
            withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(item, null, null) } }
            throw e
        }
        return withContext(Dispatchers.IO) { displayName(resolver, item) } ?: name
    }

    /** このアルバム内で(このアプリから見える)既存のファイル名。 */
    private fun existingNames(resolver: ContentResolver): Set<String> {
        val names = HashSet<String>()
        resolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
            arrayOf(relativePath),
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                cursor.getString(0)?.let(names::add)
            }
        }
        return names
    }

    private fun displayName(resolver: ContentResolver, item: Uri): String? =
        runCatching {
            resolver.query(item, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun mimeTypeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}

/** [uri] を新規に開いて書き込む。受信データは都度書き込み、メモリに全量を載せない。 */
private suspend fun writeTo(
    resolver: ContentResolver,
    uri: Uri,
    name: String,
    write: suspend (OutputStream) -> Unit,
) {
    val output = withContext(Dispatchers.IO) { resolverCall { resolver.openOutputStream(uri, "w") } }
        ?: throw IOException("書き込みを開始できません: $name")
    withContext(Dispatchers.IO) { output.buffered().use { write(it) } }
}

/**
 * ContentResolver / DocumentFile の呼び出しで投げられる実行時例外(権限の取り消し等)を [IOException] に変換する。
 * 呼び出し側は [IOException] だけを扱えばよい。
 */
private inline fun <T> resolverCall(block: () -> T): T =
    try {
        block()
    } catch (e: SecurityException) {
        throw IOException("保存先にアクセスできません: ${e.message}", e)
    } catch (e: IllegalArgumentException) {
        throw IOException("保存先にアクセスできません: ${e.message}", e)
    } catch (e: UnsupportedOperationException) {
        throw IOException("保存先にアクセスできません: ${e.message}", e)
    }
