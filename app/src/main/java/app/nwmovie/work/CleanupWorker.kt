package app.nwmovie.work

import android.content.Context
import androidx.work.*
import app.nwmovie.data.CacheManager
import java.util.concurrent.TimeUnit

class CleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        CacheManager.autoCleanOffline(applicationContext)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<CleanupWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("nw_cleanup", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
