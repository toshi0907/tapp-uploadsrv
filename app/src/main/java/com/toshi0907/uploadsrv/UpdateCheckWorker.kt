package com.toshi0907.uploadsrv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 6時間ごとにバックグラウンドで更新を確認し、新しいビルドがあれば通知する。
 * 通知をタップするとアプリが開き、ダウンロード→インストーラ起動まで進む。
 */
class UpdateCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val release = UpdateManager.check(applicationContext) ?: return Result.success()
        val settings = AppSettings(applicationContext)
        // 同じビルドについては1回だけ通知する
        if (release.buildNumber > settings.lastNotifiedUpdateBuild) {
            notifyUpdate(applicationContext, release)
            settings.lastNotifiedUpdateBuild = release.buildNumber
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "update-check"
        private const val CHANNEL_ID = "update"
        private const val NOTIFICATION_ID = 2
        private const val INTERVAL_HOURS = 6L

        /** 定期確認を登録する(登録済みならそのまま)。 */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        private fun notifyUpdate(context: Context, release: ReleaseInfo) {
            val manager = context.getSystemService(NotificationManager::class.java)
            // 通知が許可されていない場合は、アプリを開いたときの確認で気づける
            if (!manager.areNotificationsEnabled()) return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "アプリの更新", NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val startUpdate = PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java)
                    .setAction(UpdateManager.ACTION_START_UPDATE)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("新しいビルド #${release.buildNumber} があります")
                .setContentText("タップしてダウンロード・インストール")
                .setContentIntent(startUpdate)
                .setAutoCancel(true)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }
}
