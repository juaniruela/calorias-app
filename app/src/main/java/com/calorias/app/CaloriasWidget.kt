package com.calorias.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.color.ColorProvider

private val TXT = ColorProvider(day = Color(0xFF1B1B1B), night = Color(0xFFF2F2F2))
private val MUTED = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFFBDBDBD))
private val ACCENT = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF81C784))
private val RED = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFEF9A9A))
private val GREEN = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF81C784))
private val BG = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1E1E1E))
private val CHIP = ColorProvider(day = Color(0xFFE8F5E9), night = Color(0xFF2E3B2F))

data class WidgetData(
    val running: Boolean,
    val activityLabel: String,
    val summary: DaySummary?,
    val tip: String
) {
    companion object {
        suspend fun load(ctx: Context): WidgetData {
            val dao = AppDb.get(ctx).dao()
            val st = SettingsStore(ctx)
            val settings = st.load()
            val types = dao.types()
            val active = types.firstOrNull { it.id == st.activeTypeId } ?: types.firstOrNull()
            val open = dao.openDay()
            return WidgetData(
                running = open != null,
                activityLabel = active?.let { "${it.emoji} ${it.name}" } ?: "Sin actividad",
                summary = open?.let { DayController.summaryOf(ctx, it) },
                tip = if (open != null) Calc.tip(settings) else ""
            )
        }
    }
}

class CaloriasWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetData.load(context)
        provideContent { WidgetBody(data) }
    }
}

class CaloriasWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CaloriasWidget()
}

class CycleActivityAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        DayController.cycleActivity(context)
    }
}

@Composable
private fun WidgetBody(d: WidgetData) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BG)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                if (d.running) "Hoy" else "Día sin iniciar",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TXT)
            )
            // Abre una pantalla de confirmación en la app antes de iniciar o finalizar el día.
            Text(
                if (d.running) "■ Finalizar" else "▶ Iniciar",
                modifier = GlanceModifier
                    .clickable(actionStartActivity<ConfirmDayActivity>())
                    .padding(start = 12.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = ACCENT)
            )
        }

        d.summary?.let { s ->
            Text(
                "👟 ${Calc.num(s.steps.toDouble())} pasos · ${Calc.num(s.meters / 1000, 1)} km",
                style = TextStyle(fontSize = 13.sp, color = TXT)
            )
            Text(
                "🔥 ${Calc.num(s.activityKcal)} kcal act · 🍽 ${Calc.num(s.intakeKcal)} kcal",
                style = TextStyle(fontSize = 13.sp, color = TXT)
            )
            Text(
                "Saldo ${Calc.num(s.balanceKcal)} kcal · ${Calc.gramsText(s.gramsChange)}",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (s.gramsChange > 0) RED else GREEN
                )
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.padding(top = 6.dp)) {
            Text(
                d.activityLabel,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TXT)
            )
            Text(
                "  ⟳ Cambiar",
                modifier = GlanceModifier
                    .clickable(actionRunCallback<CycleActivityAction>())
                    .background(CHIP)
                    .padding(8.dp),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ACCENT)
            )
        }

        if (d.tip.isNotBlank()) {
            Text(
                "💡 ${d.tip}",
                maxLines = 2,
                style = TextStyle(fontSize = 12.sp, color = MUTED),
                modifier = GlanceModifier.padding(top = 6.dp)
            )
        }
    }
}
