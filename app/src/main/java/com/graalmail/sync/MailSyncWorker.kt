package com.graalmail.sync

import android.content.Context
import androidx.work.*
import com.graalmail.data.AppDatabase
import com.graalmail.data.MailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MailSyncWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDatabase.create(applicationContext)
            val repo = MailRepository(applicationContext, db)
            for (account in db.dao().accounts()) {
                runCatching { repo.sync(account) }
                    .getOrElse { if (runAttemptCount < 4) throw it else return@withContext Result.failure() }
            }
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "graal-mail-sync",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
