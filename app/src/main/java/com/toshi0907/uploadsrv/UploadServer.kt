package com.toshi0907.uploadsrv

import android.content.Context
import android.net.Uri
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * アップロード用HTTPサーバー(Ktor/CIO)。
 * - GET  /        アップロード画面(assets/index.html)
 * - POST /upload  multipart/form-data を受信し、保存先フォルダへストリーミングで書き込む
 */
class UploadServer(context: Context, private val port: Int) {
    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    private val indexHtml: String by lazy {
        appContext.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private var server: EmbeddedServer<*, *>? = null

    /** サーバーを起動する。ポート使用中などで失敗した場合は例外を投げる。 */
    fun start() {
        val engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            routing {
                get("/") {
                    call.respondText(indexHtml, ContentType.Text.Html.withCharset(Charsets.UTF_8))
                }
                post("/upload") {
                    handleUpload(call)
                }
            }
        }
        engine.start(wait = false)
        server = engine
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        if (!settings.hasValidSaveFolder()) {
            call.respondText(
                "保存先フォルダが未設定、またはアクセス権限がありません",
                status = HttpStatusCode.ServiceUnavailable,
            )
            return
        }
        val saved = mutableListOf<String>()
        try {
            // Ktor 3 のファイルパートは既定で50MBに制限されるため、上限を外す(動画対応)
            val multipart = call.receiveMultipart(formFieldLimit = Long.MAX_VALUE)
            multipart.forEachPart { part ->
                try {
                    if (part is PartData.FileItem) {
                        val name = sanitizeFileName(part.originalFileName)
                        if (name != null) {
                            saveFile(name) { output -> part.provider().copyTo(output) }
                            saved += name
                        }
                    }
                } finally {
                    part.dispose()
                }
            }
        } catch (e: IOException) {
            call.respondText(
                "保存に失敗しました: ${e.message}",
                status = HttpStatusCode.InternalServerError,
            )
            return
        }
        if (saved.isEmpty()) {
            call.respondText("ファイルがありません", status = HttpStatusCode.BadRequest)
        } else {
            call.respondText("${saved.size}件保存しました: ${saved.joinToString()}")
        }
    }

    /**
     * 保存先フォルダに [name] で書き込む。同名ファイルがあれば上書きする。
     */
    private suspend fun saveFile(name: String, write: suspend (OutputStream) -> Unit) {
        val target: Uri = withContext(Dispatchers.IO) {
            val folder = settings.saveFolder() ?: throw IOException("保存先フォルダを開けません")
            val existing = folder.findFile(name)
            when {
                existing != null && existing.isFile -> existing.uri
                existing != null -> throw IOException("同名のフォルダがあります: $name")
                // MIMEを octet-stream にして、プロバイダに拡張子を付け替えられないようにする
                else -> folder.createFile("application/octet-stream", name)?.uri
                    ?: throw IOException("ファイルを作成できません: $name")
            }
        }
        // "wt": 既存ファイルを切り詰めて上書き
        val output = withContext(Dispatchers.IO) {
            appContext.contentResolver.openOutputStream(target, "wt")
        } ?: throw IOException("書き込みを開始できません: $name")
        // 受信データは都度書き込み、メモリに全量を載せない
        withContext(Dispatchers.IO) { output.buffered().use { write(it) } }
    }

    companion object {
        /** パス区切りや制御文字を除いたファイル名を返す。使えない名前なら null。 */
        fun sanitizeFileName(original: String?): String? {
            val base = original
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.filterNot { it.isISOControl() }
                ?.trim()
                ?: return null
            return base.takeUnless { it.isEmpty() || it == "." || it == ".." }
        }
    }
}
