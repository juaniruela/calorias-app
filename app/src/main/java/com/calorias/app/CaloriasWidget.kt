package com.calorias.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.LocalDate

private val TXT = ColorProvider(day = Color(0xFF1B1B1B), night = Color(0xFFF2F2F2))
private val MUTED = ColorProvider(day = Color(0xFF5F6368), night = Color(0xFFBDBDBD))
private val ACCENT = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF81C784))
private val RED = ColorProvider(day = Color(0xFFC62828), night = Color(0xFFEF9A9A))
private val GREEN = ColorProvider(day = Color(0xFF2E7D32), night = Color(0xFF81C784))
private val BG = ColorProvider(day = Color(0xFFFFFFFF), night = Color(0xFF1E1E1E))

/** Datos del widget: la jornada en curso o, si no hay ninguna abierta, la última de hoy (resultados finales). */
data class WidgetData(
    val running: Boolean,
    val statusLabel: String,
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
            val day = open ?: dao.lastDayOf(LocalDate.now().toString())
            val endedAt = day?.endedAt
            val status = when {
                open != null -> "En curso"
                endedAt != null -> "Finalizado ${Calc.hhmm(endedAt)}"
                else -> "Sin datos hoy"
            }
            return WidgetData(
                running = open != null,
                statusLabel = status,
                activityLabel = active?.let { "${it.emoji} ${it.name}" } ?: "Sin actividad",
                summary = day?.let { DayController.summaryOf(ctx, it) },
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

@Composable
private fun WidgetBody(d: WidgetData) {
    val ctx = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(BG)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                "Hoy · ${d.statusLabel}",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TXT)
            )
            // Abre una pantalla de confirmación en la app antes de iniciar o finalizar el día.
            Text(
                if (d.running) "■ Finalizar" else "▶ Iniciar",
                modifier = GlanceModifier
                    .clickable(actionStartActivity(Intent(ctx, ConfirmDayActivity::class.java)))
                    .padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, color = ACCENT)
            )
        }

        d.summary?.let { s ->
            Text(
                "👟 ${Calc.num(s.steps.toDouble())} pasos · ${Calc.num(s.meters / 1000, 2)} km · ${s.activeSeconds / 60} min",
                modifier = GlanceModifier.padding(top = 4.dp),
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = TXT)
            )
            Text(
                "🍽 ${Calc.num(s.intakeKcal)} kcal (${s.meals} comidas)",
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = TXT)
            )
            Text(
                "🔥 ${Calc.num(s.bmrKcal + s.activityKcal)} kcal gastadas (actividad ${Calc.num(s.activityKcal)})",
                maxLines = 1,
                style = TextStyle(fontSize = 12.sp, color = TXT)
            )
            Text(
                "Saldo ${Calc.num(s.balanceKcal)} kcal · ${Calc.gramsText(s.gramsChange)}",
                maxLines = 1,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = if (s.gramsChange > 0) RED else GREEN
                )
            )

            Text(
                "Actividades",
                modifier = GlanceModifier.padding(top = 6.dp),
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TXT)
            )
            s.byType
                .filter { it.seconds > 0 || it.meters > 0 }
                .take(6)
                .forEach { line ->
                    Text(
                        "${line.type.emoji} ${line.type.name}: ${Calc.num(line.seconds / 60.0)} min · " +
                            "${Calc.num(line.meters / 1000, 2)} km · ${Calc.num(line.kcal)} kcal",
                        maxLines = 1,
                        style = TextStyle(fontSize = 11.sp, color = TXT)
                    )
                }
        }

        if (d.running) {
            Text(
                "Actividad actual: ${d.activityLabel}",
                modifier = GlanceModifier.padding(top = 6.dp),
                maxLines = 1,
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 12.sp, color = ACCENT)
            )
        }

        if (d.tip.isNotBlank()) {
            Text(
                "💡 ${d.tip}",
                maxLines = 2,
                style = TextStyle(fontSize = 11.sp, color = MUTED),
                modifier = GlanceModifier.padding(top = 4.dp)
            )
        }
    }
}
