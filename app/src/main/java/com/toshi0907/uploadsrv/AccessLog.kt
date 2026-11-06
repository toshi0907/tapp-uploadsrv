package com.toshi0907.uploadsrv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 接続ログ(診断用)。どの端末から・どんな通信が届き・どう終わったかを新しい順に保持する。
 * 端末内のメモリにだけ置き、アプリのプロセスが終わると消える。
 */
object AccessLog {
    data class Entry(val time: Long, val client: String, val message: String)

    private const val MAX_ENTRIES = 50

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun add(client: String, message: String) {
        val entry = Entry(System.currentTimeMillis(), client, message)
        _entries.update { (listOf(entry) + it).take(MAX_ENTRIES) }
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
