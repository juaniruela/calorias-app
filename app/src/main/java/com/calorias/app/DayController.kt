package com.calorias.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import java.time.LocalDate

/** Lógica de inicio/fin de jornada, cambio de actividad y resúmenes. Usada por la app, el widget y el worker. */
object DayController {
    fun now(): Long = System.currentTimeMillis()

    suspend fun startDay(ctx: Context) {
        val dao = AppDb.get(ctx).dao()
        if (dao.openDay() != null) return
        val st = SettingsStore(ctx)
        val def = dao.types().firstOrNull { it.isDefault }
        if (def != null) st.activeTypeId = def.id
        dao.insertDay(DayRecord(date = LocalDate.now().toString(), startedAt = now()))
        TrackingService.start(ctx)
        refreshWidget(ctx)
    }

    suspend fun endDay(ctx: Context) {
        val dao = AppDb.get(ctx).dao()
        val open = dao.openDay() ?: return
        val closed = open.copy(endedAt = now())
        dao.updateDay(closed)
        TrackingService.stop(ctx)
        Notif.summary(ctx, summaryOf(ctx, closed))
        refreshWidget(ctx)
    }

    suspend fun cycleActivity(ctx: Context) {
        val types = AppDb.get(ctx).dao().types()
        if (types.isEmpty()) return
        val st = SettingsStore(ctx)
        val i = types.indexOfFirst { it.id == st.activeTypeId }
        st.activeTypeId = types[(i + 1) % types.size].id
        refreshWidget(ctx)
    }

    suspend fun summaryOf(ctx: Context, day: DayRecord): DaySummary {
        val dao = AppDb.get(ctx).dao()
        return Calc.summarize(
            SettingsStore(ctx).load(), day, dao.types(),
            dao.dayActivities(day.id), dao.food(day.id), now()
        )
    }

    suspend fun refreshWidget(ctx: Context) {
        CaloriasWidget().updateAll(ctx)
    }
}
