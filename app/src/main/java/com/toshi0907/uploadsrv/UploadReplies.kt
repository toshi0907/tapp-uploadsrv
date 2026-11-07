package com.toshi0907.uploadsrv

import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** アップロード1件への応答。 */
data class UploadReply(val status: HttpStatusCode, val text: String) {
    /** 処理を終えた(保存した、または保存対象外と判定した)か。同じ送信を繰り返しても結果が変わらないもの。 */
    val finished: Boolean get() = status.isSuccess() || status == HttpStatusCode.UnsupportedMediaType
}

/**
 * ブラウザが付けた送信ID ごとの結果を覚えておき、再送されたアップロードを二重に保存しないようにする。
 * 保存は終わったのに応答だけが届かずブラウザが再送した場合に、`名前 (1).jpg` のような重複を作らないため。
 * - 処理を終えた ID には、本文を読み捨てたうえで前回の応答を返す
 * - 同じ ID を処理中なら、その結果を待つ(失敗に終わったら改めて処理する)
 */
class UploadReplies(private val capacity: Int = DEFAULT_CAPACITY) {
    private val mutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<UploadReply?>>()
    private val completed = object : LinkedHashMap<String, UploadReply>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, UploadReply>?): Boolean =
            size > capacity
    }

    private sealed interface Slot {
        class Done(val reply: UploadReply) : Slot
        class Wait(val running: CompletableDeferred<UploadReply?>) : Slot
        class Mine(val result: CompletableDeferred<UploadReply?>) : Slot
    }

    /**
     * [id] の送信を処理する。初めての ID なら [process] を実行し、処理済みの ID なら [discardBody] を呼んでから
     * 前回の応答を返す。
     */
    suspend fun run(id: String, discardBody: suspend () -> Unit, process: suspend () -> UploadReply): UploadReply {
        while (true) {
            val slot = mutex.withLock {
                completed[id]?.let { Slot.Done(it) }
                    ?: inFlight[id]?.let { Slot.Wait(it) }
                    ?: CompletableDeferred<UploadReply?>().also { inFlight[id] = it }.let { Slot.Mine(it) }
            }
            when (slot) {
                is Slot.Done -> {
                    // 本文を読まずに応答して接続を閉じると、ブラウザ側で応答を受け取れないことがあるため読み捨てる
                    discardBody()
                    return slot.reply
                }
                // 処理中の送信が終わるのを待ってから、改めて状態を確認する
                is Slot.Wait -> slot.running.await()
                is Slot.Mine -> {
                    var reply: UploadReply? = null
                    try {
                        reply = process()
                        return reply
                    } finally {
                        val finished = reply?.takeIf { it.finished }
                        withContext(NonCancellable) {
                            mutex.withLock {
                                inFlight.remove(id)
                                if (finished != null) completed[id] = finished
                            }
                        }
                        // 失敗(null)なら、待っている側が改めて処理する
                        slot.result.complete(finished)
                    }
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_CAPACITY = 1000

        /** ブラウザが付ける送信IDとして受け付ける形式。 */
        private val ID_FORMAT = Regex("^[A-Za-z0-9_-]{8,64}$")

        fun isValidId(id: String): Boolean = ID_FORMAT.matches(id)
    }
}
