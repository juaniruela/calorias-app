package com.calorias.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/** A la hora configurada cierra la jornada si sigue abierta y envía el resumen. */
class SummaryWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (AppDb.get(applicationContext).dao().openDay() != null) {
            DayController.endDay(applicationContext)
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context, hour: Int, minute: Int) {
            val now = LocalDateTime.now()
            var target = now.toLocalDate().atTime(hour, minute)
            if (!target.isAfter(now)) target = target.plusDays(1)
            val delayMs = Duration.between(now, target).toMillis()
            val req = PeriodicWorkRequestBuilder<SummaryWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork("daily-summary", ExistingPeriodicWorkPolicy.REPLACE, req)
        }
    }
}
