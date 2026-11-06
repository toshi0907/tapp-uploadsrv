package com.toshi0907.uploadsrv

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** サーバーの稼働状態。 */
sealed interface ServerState {
    data object Stopped : ServerState
    data class Running(val port: Int) : ServerState
    data class Failed(val message: String) : ServerState
}

/**
 * アプリ全体で1つのアップロードサーバーを起動・停止する。
 * 起動/停止は複数箇所から呼ばれうるため Mutex で直列化する。
 */
object ServerController {
    private val mutex = Mutex()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: UploadServer? = null
    private val _state = MutableStateFlow<ServerState>(ServerState.Stopped)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    /** サーバーを起動する。成功時は [stopAsync] に渡すトークン、失敗時は null を返す。 */
    suspend fun start(context: Context, port: Int): Any? = mutex.withLock {
        // ソケットのbindや停止待ちはブロッキングなのでIOスレッドで行う
        withContext(Dispatchers.IO) { server?.stop() }
        server = null
        val newServer = UploadServer(context, port)
        try {
            withContext(Dispatchers.IO) { newServer.start() }
            server = newServer
            _state.value = ServerState.Running(port)
            newServer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { runCatching { newServer.stop() } }
            _state.value = ServerState.Failed("起動できませんでした(ポート$port): ${e.message}")
            null
        }
    }

    /**
     * サービス破棄時など、呼び出し元のスコープに依存せず停止する。
     * [token] は [start] の戻り値。その後に別のサーバーが起動されていれば何もしない
     * (停止直後の再起動を、古い停止要求が止めてしまわないようにする)。
     */
    fun stopAsync(token: Any?) {
        if (token == null) return
        appScope.launch {
            mutex.withLock {
                if (server === token) stopLocked()
            }
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    private suspend fun stopLocked() {
        withContext(Dispatchers.IO) { server?.stop() }
        server = null
        _state.value = ServerState.Stopped
    }
}
