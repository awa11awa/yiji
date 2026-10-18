package cn.yiji.app.platform

import android.content.Context
import androidx.work.*
import cn.yiji.app.YijiApplication
import java.util.concurrent.TimeUnit

class ExtractionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = (applicationContext as YijiApplication).repository
        repository.dao.recoverJobs()
        repository.processQueued()
        val remaining = repository.dao.allJobs()
        if (remaining.any { it.state == "QUEUED" || it.state == "RETRY" && it.nextAttemptAt <= System.currentTimeMillis() }) {
            ExtractionWork.schedule(applicationContext)
            return Result.success()
        }
        return if (remaining.any { it.state == "RETRY" }) Result.retry() else Result.success()
    }
}

object ExtractionWork {
    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("yiji-message-extraction",
            ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
