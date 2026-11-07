package com.toshi0907.uploadsrv

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.cancellation.CancellationException

/** 保存できない種類のファイル(ギャラリー方式での写真・動画以外)。 */
class UnsupportedFileException(message: String) : IOException(message)

/**
 * アップロードされたファイルの保存先。同名ファイルがあれば上書きせず、`name (1).ext` のような別名で保存する。
 */
interface UploadStorage {
    /** [name] を元にした名前で保存し、実際に保存した名前を返す。保存はできたが名前を確認できなければ null。 */
    suspend fun save(name: String, write: suspend (OutputStream) -> Unit): String?

    companion object {
        /**
         * 名前の決定から保存先の項目作成までを直列化する。同時に同名のアップロードが来ても、
         * 先に作られた(空の)項目が一覧に見えるため、アプリ側で重複しない名前を決められる。
         */
        internal val nameLock = Mutex()

        /** 現在の設定に応じた保存先を返す。保存できない状態なら [IOException]。 */
        fun from(context: Context, settings: AppSettings): UploadStorage {
            // サーバー起動の可否と同じ判定を使い、起動できるのに保存できない、というずれを防ぐ
            if (!settings.hasValidDestination()) {
                throw IOException(
                    when (settings.saveMode) {
                        SaveMode.GALLERY -> "アルバム名が不正です"
                        SaveMode.FOLDER -> "保存先フォルダが未設定、またはアクセス権限がありません"
                    },
                )
            }
            val appContext = context.applicationContext
            return when (settings.saveMode) {
                SaveMode.GALLERY -> {
                    // saveMode が GALLERY を返すのは Android 10 以上のみ。lint 向けに API レベルを直接確認する
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        throw IOException("この端末ではギャラリーに保存できません")
                    }
                    GalleryStorage(appContext, settings.albumName)
                }
                SaveMode.FOLDER -> {
                    val uri = settings.saveFolderUri ?: throw IOException("保存先フォルダを開けません")
                    FolderStorage(appContext, uri)
                }
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
                val suffix = " ($i)$ext"
                // 番号を付けてもファイル名の上限(UTF-8で255バイト)に収まるよう、元の名前の側を短くする
                val candidate = truncateUtf8(base, MAX_NAME_BYTES - utf8Length(suffix)) + suffix
                if (candidate.lowercase() !in lower) return candidate
            }
            throw IOException("別名を決められません: $name")
        }

        /** [text] を UTF-8 で [maxBytes] バイト以内に切り詰める。文字(サロゲートペア含む)の途中では切らない。 */
        private fun truncateUtf8(text: String, maxBytes: Int): String {
            if (maxBytes <= 0) return ""
            var bytes = 0
            var end = 0
            while (end < text.length) {
                val codePoint = text.codePointAt(end)
                val size = utf8Length(String(Character.toChars(codePoint)))
                if (bytes + size > maxBytes) break
                bytes += size
                end += Character.charCount(codePoint)
            }
            return text.substring(0, end)
        }

        private fun utf8Length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        private const val MAX_SUFFIX = 9999

        /** 一般的なファイルシステムのファイル名の上限(バイト)。 */
        private const val MAX_NAME_BYTES = 255
    }
}

/** SAFで選んだフォルダ([treeUri])に保存する。 */
private class FolderStorage(private val context: Context, private val treeUri: Uri) : UploadStorage {
    override suspend fun save(name: String, write: suspend (OutputStream) -> Unit): String? {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val file = UploadStorage.nameLock.withLock {
            withContext(Dispatchers.IO) {
                val target = UploadStorage.uniqueName(name, resolverCall { childNames(resolver) })
                // MIMEを octet-stream にして、プロバイダに拡張子を付け替えられないようにする
                val created = resolverCall {
                    DocumentsContract.createDocument(resolver, parent, "application/octet-stream", target)
                }
                created ?: throw IOException("ファイルを作成できません: $name")
            }
        }
        try {
            writeTo(resolver, file, name, write)
        } catch (e: Throwable) {
            // 書きかけのファイルを残さない
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(resolver, file) }
            }
            throw e
        }
        return withContext(Dispatchers.IO) { displayName(resolver, file, DocumentsContract.Document.COLUMN_DISPLAY_NAME) }
    }

    /** フォルダ直下の名前の一覧(ファイル・フォルダとも)。1回の問い合わせで取得する。 */
    private fun childNames(resolver: ContentResolver): Set<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val names = HashSet<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    cursor.getString(0)?.let(names::add)
                }
            } ?: throw IOException("保存先フォルダの内容を取得できません")
        return names
    }
}

/**
 * MediaStore 経由で `Pictures/<アルバム名>/` に保存する(Android 10 以降)。
 * ギャラリーアプリにはフォルダ単位のアルバムとして表示される。写真・動画以外は保存しない。
 */
@RequiresApi(Build.VERSION_CODES.Q)
private class GalleryStorage(private val context: Context, album: String) : UploadStorage {
    private val relativePath = "${Environment.DIRECTORY_PICTURES}/$album/"

    override suspend fun save(name: String, write: suspend (OutputStream) -> Unit): String? {
        val mime = mimeTypeOf(name)
        val collection = when {
            mime == null -> null
            mime.startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            mime.startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> null
        } ?: throw UnsupportedFileException("写真・動画以外のため保存しませんでした")

        val resolver = context.contentResolver
        val item = UploadStorage.nameLock.withLock {
            withContext(Dispatchers.IO) {
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
        }
        try {
            writeTo(resolver, item, name, write)
            withContext(Dispatchers.IO) {
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                val updated = resolverCall { resolver.update(item, published, null, null) }
                // 公開できないとギャラリーに出ず、保留のまま後で自動削除されるため失敗として扱う
                if (updated == 0) throw IOException("ギャラリーに公開できません: $name")
            }
        } catch (e: Throwable) {
            // 書きかけの項目を残さない
            withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.delete(item, null, null) } }
            throw e
        }
        return withContext(Dispatchers.IO) { displayName(resolver, item, MediaStore.MediaColumns.DISPLAY_NAME) }
    }

    /** このアルバム内で(このアプリから見える)既存のファイル名。書き込み中・ゴミ箱の項目も含める。 */
    private fun existingNames(resolver: ContentResolver): Set<String> {
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
        val args = arrayOf(relativePath)
        val cursor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val query = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(files, projection, query, null)
        } else {
            resolver.query(files, projection, selection, args, null)
        }
        val names = HashSet<String>()
        cursor?.use {
            while (it.moveToNext()) {
                it.getString(0)?.let(names::add)
            }
        } ?: throw IOException("ギャラリーの内容を取得できません")
        return names
    }

    private fun mimeTypeOf(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    }
}

/** [uri] の表示名を読み直す。取得できなければ null。 */
private fun displayName(resolver: ContentResolver, uri: Uri, column: String): String? =
    runCatching {
        resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

/** [uri] を新規に開いて書き込む。受信データは都度書き込み、メモリに全量を載せない。 */
private suspend fun writeTo(
    resolver: ContentResolver,
    uri: Uri,
    name: String,
    write: suspend (OutputStream) -> Unit,
) {
    // 開いてから閉じるまでを同じ withContext で行い、間でキャンセルされてもストリームを閉じ漏らさない
    withContext(Dispatchers.IO) {
        val output = resolverCall { resolver.openOutputStream(uri, "w") }
            ?: throw IOException("書き込みを開始できません: $name")
        output.buffered().use { write(it) }
    }
}

/**
 * ContentResolver / DocumentsProvider の呼び出しで投げられる実行時例外(権限の取り消し、プロバイダ内部の失敗等)を
 * [IOException] に変換する。呼び出し側は [IOException] だけを扱えばよい。コルーチンのキャンセルはそのまま伝える。
 */
private inline fun <T> resolverCall(block: () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        throw IOException("保存先にアクセスできません: ${e.message}", e)
    }
