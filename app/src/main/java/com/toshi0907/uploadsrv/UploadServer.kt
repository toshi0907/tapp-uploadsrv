package com.toshi0907.uploadsrv

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.discard
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
    private val replies = UploadReplies()
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
                    // 1ファイルごとに接続を閉じる。使い回した接続がサーバー側で既に閉じていて
                    // 次のファイルの送信が失敗する(ブラウザは POST を自動で再送しない)のを防ぐ
                    call.response.headers.append(HttpHeaders.Connection, "close")
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
        // 通信エラーでブラウザが再送してきた場合に二重に保存しないよう、送信IDごとに結果を覚えておく
        val id = call.request.headers[UPLOAD_ID_HEADER]?.takeIf(UploadReplies::isValidId)
        val reply = if (id == null) {
            receiveUpload(call)
        } else {
            replies.run(id, discardBody = { call.receiveChannel().discard() }) { receiveUpload(call) }
        }
        call.respondText(reply.text, status = reply.status)
    }

    private suspend fun receiveUpload(call: ApplicationCall): UploadReply {
        // 保存方式は設定の変更を次のアップロードから反映するため、リクエストごとに決める
        val storage = try {
            UploadStorage.from(appContext, settings)
        } catch (e: IOException) {
            // 本文を読まずに応答して接続を閉じると、ブラウザが応答を受け取れず通信エラーになることがあるため読み捨てる
            call.receiveChannel().discard()
            return UploadReply(HttpStatusCode.ServiceUnavailable, e.message ?: "保存先を準備できません")
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
                                saved += when (actual) {
                                    name -> "保存: $name"
                                    null -> "保存: $name(保存名を確認できません)"
                                    else -> "保存: $name → $actual"
                                }
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
            return UploadReply(
                HttpStatusCode.InternalServerError,
                "保存に失敗しました: ${e.message}" + if (done.isEmpty()) "" else "\n$done",
            )
        }
        val lines = (saved + skipped).joinToString("\n")
        return when {
            saved.isNotEmpty() -> UploadReply(HttpStatusCode.OK, lines)
            skipped.isNotEmpty() -> UploadReply(HttpStatusCode.UnsupportedMediaType, lines)
            else -> UploadReply(HttpStatusCode.BadRequest, "ファイルがありません")
        }
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"

        /** ブラウザがファイルごとに付ける送信ID(再送時も同じ値)。 */
        private const val UPLOAD_ID_HEADER = "X-Upload-Id"

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
