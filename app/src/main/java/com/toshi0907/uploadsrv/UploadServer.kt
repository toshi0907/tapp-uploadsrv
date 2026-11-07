package com.toshi0907.uploadsrv

import android.content.Context
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
import java.io.File
import java.io.IOException

/**
 * アップロード用HTTPサーバー(Ktor/CIO)。
 * - GET  /        アップロード画面(assets/index.html)
 * - POST /upload  multipart/form-data を受信し、保存先([UploadStorage])へストリーミングで書き込む
 *
 * Ktor はループバックの空きポートでだけ待ち受け、公開ポートは [FrontServer] が受けて中継する
 * (同じポートで HTTP と HTTPS を受け付け、接続ログを取るため)。
 */
class UploadServer(context: Context, private val port: Int) {
    private val appContext = context.applicationContext
    private val settings = AppSettings(appContext)
    private val indexHtml: String by lazy {
        appContext.assets.open("index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
    private var server: EmbeddedServer<*, *>? = null
    private var front: FrontServer? = null

    /** HTTPS でも接続できるか([start] 後に有効)。 */
    val httpsAvailable: Boolean get() = front?.httpsAvailable == true

    /** サーバーを起動する。ポート使用中などで失敗した場合は例外を投げる(後始末は [stop])。 */
    suspend fun start() {
        // HTTPS を準備できなくても HTTP だけで動かす
        val ssl = runCatching { TlsIdentity.sslContext(File(appContext.filesDir, "tls"), NetworkUtils.localIpv4Addresses()) }
            .onFailure { AccessLog.add("-", "HTTPSの証明書を準備できませんでした: ${it.message}") }
            .getOrNull()
        // 公開ポートを先に確保する(Ktorの空きポート割り当てに同じ番号を取られないように)
        val publicServer = FrontServer(port, ssl)
        front = publicServer
        publicServer.bind()

        val engine = embeddedServer(CIO, port = 0, host = LOOPBACK) {
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
        val backendPort = engine.engine.resolvedConnectors().first().port
        publicServer.start(backendPort)
    }

    fun stop() {
        front?.stop()
        front = null
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 2_000)
        server = null
    }

    private suspend fun handleUpload(call: ApplicationCall) {
        // 保存方式は設定の変更を次のアップロードから反映するため、リクエストごとに決める
        val storage = try {
            UploadStorage.from(appContext, settings)
        } catch (e: IOException) {
            call.respondText(e.message ?: "保存先を準備できません", status = HttpStatusCode.ServiceUnavailable)
            return
        }
        val saved = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        try {
            // Ktor 3 のファイルパートは既定で50MBに制限されるため、上限を外す(動画対応)
            val multipart = call.receiveMultipart(formFieldLimit = Long.MAX_VALUE)
            multipart.forEachPart { part ->
                try {
                    if (part is PartData.FileItem) {
                        val name = sanitizeFileName(part.originalFileName)
                        if (name != null) {
                            try {
                                val actual = storage.save(name) { output -> part.provider().copyTo(output) }
                                saved += if (actual == name) "保存: $name" else "保存: $name → $actual"
                            } catch (e: UnsupportedFileException) {
                                skipped += "スキップ: $name(${e.message})"
                            }
                        }
                    }
                } finally {
                    part.dispose()
                }
            }
        } catch (e: IOException) {
            val done = (saved + skipped).joinToString("\n")
            call.respondText(
                "保存に失敗しました: ${e.message}" + if (done.isEmpty()) "" else "\n$done",
                status = HttpStatusCode.InternalServerError,
            )
            return
        }
        val lines = (saved + skipped).joinToString("\n")
        when {
            saved.isNotEmpty() -> call.respondText(lines)
            skipped.isNotEmpty() -> call.respondText(lines, status = HttpStatusCode.UnsupportedMediaType)
            else -> call.respondText("ファイルがありません", status = HttpStatusCode.BadRequest)
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

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
