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
        val start = now()
        accrueRest(dao, start)
        dao.insertDay(DayRecord(date = LocalDate.now().toString(), startedAt = start))
        TrackingService.start(ctx)
        refreshWidget(ctx)
    }

    /**
     * Tiempo entre el fin del último día y el inicio del nuevo: se cuenta como descanso.
     * El día anterior se extiende hasta ese inicio, así el gasto basal de ese tramo se suma
     * igual que en el resto de la jornada (más lo que aporte la actividad de descanso).
     */
    private suspend fun accrueRest(dao: AppDao, newStart: Long) {
        val last = dao.lastClosedDay() ?: return
        val endedAt = last.endedAt ?: return
        val gapSec = (newStart - endedAt) / 1000
        if (gapSec <= 0) return
        dao.types().firstOrNull { it.isRest }?.let { rest ->
            dao.addActivity(last.id, rest.id, gapSec, 0.0, 0L)
        }
        dao.updateDay(last.copy(endedAt = newStart))
    }

    suspend fun endDay(ctx: Context) {
        val dao = AppDb.get(ctx).dao()
        val open = dao.openDay() ?: return
        val closed = open.copy(endedAt = now())
        dao.updateDay(closed)
        dao.closeOpenRoutes(closed.endedAt ?: now())
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
