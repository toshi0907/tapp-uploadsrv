package com.toshi0907.uploadsrv

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * 公開ポートで接続を受け、ループバック上のKtor(CIO)へ中継するフロント。
 * - 先頭バイトで HTTP と HTTPS(TLS)を判別し、同じポートで両方を受け付ける
 *   (CIO は TLS に対応しておらず、TLS が届くと無応答で切断してしまうため)
 * - TLS は内部の SSLServerSocket で復号してから CIO へ渡す
 * - 接続ごとの経過を [AccessLog] に記録する(接続できない原因の診断用)
 */
class FrontServer(
    private val port: Int,
    private val backendPort: Int,
    private val sslContext: SSLContext?,
) {
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    /** 内部TLSサーバーへ中継した接続の、ループバック側ポート → 元の接続元。 */
    private val tlsOrigins = ConcurrentHashMap<Int, String>()
    private var listener: ServerSocket? = null
    private var tlsListener: SSLServerSocket? = null

    @Volatile
    private var stopped = false

    /** HTTPS を受け付けられるか([start] 後に有効)。 */
    val httpsAvailable: Boolean get() = tlsListener != null

    /** 公開ポートで待ち受けを始める。ポート使用中などで失敗した場合は例外を投げる。 */
    fun start() {
        val front = ServerSocket()
        try {
            front.reuseAddress = true
            front.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            runCatching { front.close() }
            pool.shutdownNow()
            throw e
        }
        listener = front
        tlsListener = sslContext?.let { ctx ->
            runCatching {
                ctx.serverSocketFactory.createServerSocket(0, 50, InetAddress.getLoopbackAddress()) as SSLServerSocket
            }.onFailure { AccessLog.add("-", "HTTPSを準備できませんでした: ${it.message}") }
                .getOrNull()
        }
        tlsListener?.let { tls -> pool.execute { acceptLoop(tls) { handleTls(it as SSLSocket) } } }
        pool.execute { acceptLoop(front, ::handleFront) }
    }

    fun stop() {
        stopped = true
        runCatching { listener?.close() }
        runCatching { tlsListener?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        pool.shutdownNow()
    }

    private fun acceptLoop(server: ServerSocket, handle: (Socket) -> Unit) {
        while (!stopped) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                if (stopped || server.isClosed) return
                AccessLog.add("-", "接続の受け付けに失敗しました: ${e.message}")
                // ファイル数の上限などで失敗し続ける場合に空回りしない
                runCatching { Thread.sleep(ACCEPT_RETRY_DELAY_MS) }.onFailure { return }
                continue
            }
            sockets += socket
            try {
                pool.execute {
                    try {
                        handle(socket)
                    } catch (e: Exception) {
                        AccessLog.add(clientName(socket), "処理中にエラーが発生しました: ${e.message}")
                        closeQuietly(socket)
                    }
                }
            } catch (e: Exception) {
                // 停止処理中でスレッドプールが受け付けない
                closeQuietly(socket)
            }
        }
    }

    /** 公開ポートに届いた接続。先頭のデータを見て振り分ける。 */
    private fun handleFront(client: Socket) {
        val name = clientName(client)
        val buffer = ByteArray(BUFFER_SIZE)
        client.soTimeout = FIRST_DATA_TIMEOUT_MS
        val read = try {
            client.getInputStream().read(buffer)
        } catch (e: SocketTimeoutException) {
            AccessLog.add(name, "${FIRST_DATA_TIMEOUT_MS / 1000}秒間データが届かないため切断しました(先行接続の可能性)")
            closeQuietly(client)
            return
        } catch (e: IOException) {
            AccessLog.add(name, "データを受信する前に切断されました: ${e.message}")
            closeQuietly(client)
            return
        }
        if (read <= 0) {
            AccessLog.add(name, "データを受信しないまま切断されました")
            closeQuietly(client)
            return
        }
        client.soTimeout = 0

        val first = buffer[0].toInt() and 0xFF
        val target = if (first == TLS_HANDSHAKE) {
            val tls = tlsListener
            if (tls == null) {
                AccessLog.add(name, "HTTPS(TLS)で接続されましたが、HTTPSを準備できていないため切断しました")
                closeQuietly(client)
                return
            }
            AccessLog.add(name, "HTTPS(TLS)で接続されました")
            connect(tls.localPort) { tlsOrigins[it.localPort] = name }
        } else {
            val line = requestLine(buffer, read)
            val message = when {
                line != null -> "HTTP: $line"
                // リクエスト行が分割されて届いた場合など
                first in 'A'.code..'Z'.code -> "HTTP: (先頭行を判別できません)"
                else -> "不明なデータを受信しました(先頭: 0x%02X)".format(first)
            }
            AccessLog.add(name, message)
            connect(backendPort)
        }
        if (target == null) {
            AccessLog.add(name, "内部サーバーに接続できませんでした")
            closeQuietly(client)
            return
        }
        forward(name, client, target, buffer, read, logResponse = first != TLS_HANDSHAKE)
    }

    /** 内部TLSサーバーに届いた接続。復号してからKtorへ中継する。 */
    private fun handleTls(socket: SSLSocket) {
        socket.soTimeout = FIRST_DATA_TIMEOUT_MS
        val handshakeError = try {
            socket.startHandshake()
            null
        } catch (e: IOException) {
            e
        }
        // フロントは対応表を登録してからデータを送るので、ハンドシェイクの後なら必ず引ける
        val name = tlsOrigins.remove(socket.port) ?: clientName(socket)
        if (handshakeError != null) {
            AccessLog.add(name, "HTTPSのハンドシェイクに失敗しました(証明書を拒否された可能性): ${handshakeError.message}")
            closeQuietly(socket)
            return
        }
        val buffer = ByteArray(BUFFER_SIZE)
        val read = try {
            socket.inputStream.read(buffer)
        } catch (e: SocketTimeoutException) {
            AccessLog.add(name, "HTTPS: ${FIRST_DATA_TIMEOUT_MS / 1000}秒間リクエストが届かないため切断しました(先行接続の可能性)")
            closeQuietly(socket)
            return
        } catch (e: IOException) {
            AccessLog.add(name, "HTTPS: リクエストを受信する前に切断されました: ${e.message}")
            closeQuietly(socket)
            return
        }
        if (read <= 0) {
            AccessLog.add(name, "HTTPS: リクエストを受信しないまま切断されました")
            closeQuietly(socket)
            return
        }
        socket.soTimeout = 0
        AccessLog.add(name, "HTTPS: ${requestLine(buffer, read) ?: "(リクエストを解釈できません)"}")
        val backend = connect(backendPort)
        if (backend == null) {
            AccessLog.add(name, "内部サーバーに接続できませんでした")
            closeQuietly(socket)
            return
        }
        forward(name, socket, backend, buffer, read, logResponse = true)
    }

    /** 受信済みの先頭データを [upstream] へ送り、以降を中継する。失敗したら両方閉じる。 */
    private fun forward(name: String, client: Socket, upstream: Socket, head: ByteArray, len: Int, logResponse: Boolean) {
        try {
            upstream.getOutputStream().apply { write(head, 0, len); flush() }
        } catch (e: IOException) {
            AccessLog.add(name, "内部サーバーへの送信に失敗しました: ${e.message}")
            closeQuietly(client)
            closeQuietly(upstream)
            return
        }
        relay(name, client, upstream, logResponse)
    }

    private fun connect(targetPort: Int, beforeUse: (Socket) -> Unit = {}): Socket? {
        if (stopped) return null
        val socket = Socket()
        sockets += socket
        return try {
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), targetPort), CONNECT_TIMEOUT_MS)
            beforeUse(socket)
            socket
        } catch (e: IOException) {
            closeQuietly(socket)
            null
        }
    }

    /**
     * [client] と [upstream] の間で双方向にデータを流す。呼び出したスレッドが「上り」を担当し、
     * 「下り」は別スレッドで流す。両方向が終わったら両方のソケットを閉じる。
     */
    private fun relay(name: String, client: Socket, upstream: Socket, logResponse: Boolean) {
        val remaining = AtomicInteger(2)
        val finish = {
            if (remaining.decrementAndGet() == 0) {
                closeQuietly(client)
                closeQuietly(upstream)
            }
        }
        val downstream = Runnable {
            pump(upstream.getInputStream(), client.getOutputStream(), onChunk = { buf, len ->
                if (logResponse) statusLine(buf, len)?.let { AccessLog.add(name, "応答: $it") }
            }) { error ->
                if (error != null && isUnexpected(client, upstream)) {
                    AccessLog.add(name, "応答の送信中に切断されました: ${error.message}")
                }
                halfClose(client, upstream)
            }
            finish()
        }
        try {
            pool.execute(downstream)
        } catch (e: Exception) {
            // 停止処理中でスレッドプールが受け付けない
            closeQuietly(client)
            closeQuietly(upstream)
            return
        }
        // 同じ接続(keep-alive)で続けて送られたリクエストも記録する。最初の1件は呼び出し元で記録済み
        val prefix = if (client is SSLSocket) "HTTPS: " else "HTTP: "
        pump(client.getInputStream(), upstream.getOutputStream(), onChunk = { buf, len ->
            requestLine(buf, len)?.let { AccessLog.add(name, prefix + it) }
        }) { error ->
            if (error != null && isUnexpected(client, upstream)) {
                AccessLog.add(name, "受信中に切断されました: ${error.message}")
            }
            halfClose(upstream, client)
        }
        finish()
    }

    private fun pump(
        input: InputStream,
        output: OutputStream,
        onChunk: (ByteArray, Int) -> Unit,
        onEnd: (IOException?) -> Unit,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        val error = try {
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                // 先頭がリクエスト行/応答行のチャンクだけを記録する(本文の途中は対象外)
                onChunk(buffer, n)
                output.write(buffer, 0, n)
                output.flush()
            }
            null
        } catch (e: IOException) {
            e
        }
        onEnd(error)
    }

    /** [to] への送信を終える。TLSソケットは片側だけ閉じられないので両方閉じる。 */
    private fun halfClose(to: Socket, other: Socket) {
        val ok = runCatching { to.shutdownOutput() }.isSuccess
        if (!ok || to is SSLSocket) {
            closeQuietly(to)
            closeQuietly(other)
        }
    }

    /** 自分で閉じたソケットによるエラーは記録しない。 */
    private fun isUnexpected(a: Socket, b: Socket): Boolean = !stopped && !a.isClosed && !b.isClosed

    private fun closeQuietly(socket: Socket) {
        runCatching { socket.close() }
        sockets -= socket
    }

    private fun clientName(socket: Socket): String =
        socket.inetAddress?.hostAddress ?: "?"

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val TLS_HANDSHAKE = 0x16
        private const val FIRST_DATA_TIMEOUT_MS = 60_000
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val MAX_LOG_LINE = 200
        private const val MAX_LINE_SCAN = 8 * 1024
        private const val ACCEPT_RETRY_DELAY_MS = 200L

        /** 先頭行が HTTP のリクエスト行(例: "GET / HTTP/1.1")なら返す。 */
        internal fun requestLine(buf: ByteArray, len: Int): String? {
            val line = firstLine(buf, len) ?: return null
            return line.takeIf { REQUEST_LINE.matches(it) }?.let(::truncate)
        }

        /** 先頭行が HTTP の応答行なら、ステータス部分(例: "200 OK")を返す。 */
        internal fun statusLine(buf: ByteArray, len: Int): String? {
            val line = firstLine(buf, len) ?: return null
            return line.takeIf { it.startsWith("HTTP/") }?.substringAfter(' ')?.let(::truncate)
        }

        /** 先頭行(改行の手前まで)。改行が無い・表示できない文字を含む場合は null。 */
        private fun firstLine(buf: ByteArray, len: Int): String? {
            val limit = minOf(len, MAX_LINE_SCAN)
            val end = (0 until limit).firstOrNull { buf[it] == CR || buf[it] == LF } ?: return null
            val text = String(buf, 0, end, Charsets.ISO_8859_1)
            return text.takeIf { s -> s.isNotEmpty() && s.all { it in ' '..'~' } }
        }

        private fun truncate(s: String): String =
            if (s.length <= MAX_LOG_LINE) s else s.take(MAX_LOG_LINE) + "…"

        private const val CR = '\r'.code.toByte()
        private const val LF = '\n'.code.toByte()

        private val REQUEST_LINE = Regex("^[A-Z]+ \\S+ HTTP/\\d(\\.\\d)?$")
    }
}
