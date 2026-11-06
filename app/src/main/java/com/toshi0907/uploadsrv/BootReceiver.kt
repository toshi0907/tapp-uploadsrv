package com.toshi0907.uploadsrv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 端末起動時にサーバーを自動起動する。
 * サーバーON設定かつ保存先フォルダの権限が有効な場合のみ起動する。
 *
 * Android 14 以降は BOOT_COMPLETED から起動できるFGS種別に制限があるが
 * (dataSync・camera・mediaPlayback 等は不可)、本アプリの specialUse は対象外。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = AppSettings(context)
        if (!settings.serverEnabled || !settings.hasValidSaveFolder()) return
        try {
            UploadService.start(context)
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException(Android 12+)等。起動できなくてもクラッシュさせない
            Log.w(TAG, "failed to start UploadService on boot", e)
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
