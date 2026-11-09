package com.zion.gallerysorter

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the gallery photo by photo and stores the result after EACH photo,
 * so a stopped scan resumes where it left off instead of starting over.
 */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val limit = inputData.getInt(KEY_LIMIT, 0)
        val db = PhotoDb.get(applicationContext)
        val done = db.processedIds()
        val pending = MediaQuery.list(applicationContext)
            .filter { it.id !in done }
            .let { if (limit > 0) it.take(limit) else it }
        val total = pending.size
        if (total == 0) return@withContext Result.success()

        tryForeground(0, total)
        val resolver = applicationContext.contentResolver
        Classifier().use { classifier ->
            pending.forEachIndexed { i, item ->
                if (isStopped) return@withContext Result.retry()
                val start = System.nanoTime()
                val bitmap = runCatching {
                    resolver.loadThumbnail(item.uri, Size(THUMB, THUMB), null)
                }.getOrNull()
                val result = runCatching { classifier.classify(item, bitmap) }
                    .getOrElse { Classification(Category.OTHER, 0.0) }
                bitmap?.recycle()
                val ms = (System.nanoTime() - start) / 1_000_000
                db.insert(item, result, ms)

                val n = i + 1
                if (n % 5 == 0 || n == total) {
                    setProgress(workDataOf(KEY_DONE to n, KEY_TOTAL to total))
                }
                if (n % 25 == 0) tryForeground(n, total)
            }
        }
        Result.success(workDataOf(KEY_DONE to total, KEY_TOTAL to total))
    }

    /** Foreground keeps a long scan alive; if Android refuses (app in background), we go on without it. */
    private suspend fun tryForeground(done: Int, total: Int) {
        runCatching { setForeground(foregroundInfo(done, total)) }
    }

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "סריקת הגלריה", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("ממיין את הגלריה")
            .setContentText("$done מתוך $total")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val UNIQUE = "scan"
        const val KEY_LIMIT = "limit"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val CHANNEL = "scan"
        private const val NOTIFICATION_ID = 42
        private const val THUMB = 640

        /** limit = 0 means the whole gallery. */
        fun start(context: Context, limit: Int, onlyWhileCharging: Boolean) {
            val request = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf(KEY_LIMIT to limit))
                .setConstraints(
                    Constraints.Builder().setRequiresCharging(onlyWhileCharging).build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
        }
    }
}
