package cn.yiji.app.platform

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cn.yiji.app.YijiApplication
import cn.yiji.app.data.Preferences
import java.util.concurrent.TimeUnit

class IgnoredCleanupWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as YijiApplication).repository.purgeExpiredIgnored()
        return Result.success()
    }
}

object IgnoredCleanup {
    private const val NAME = "yiji-ignored-cleanup"
    fun sync(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (Preferences(context).ignoredRetentionDays == 0) manager.cancelUniqueWork(NAME)
        else manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<IgnoredCleanupWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(1, TimeUnit.DAYS).build())
    }
}
