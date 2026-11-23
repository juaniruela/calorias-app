package com.calorias.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object Notif {
    private const val CH_TRACK = "tracking"
    private const val CH_SUMMARY = "summary"
    const val ID_TRACK = 1
    private const val ID_SUMMARY = 2

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_TRACK, "Seguimiento GPS", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SUMMARY, "Resumen del día", NotificationManager.IMPORTANCE_HIGH))
    }

    fun tracking(ctx: Context): Notification = NotificationCompat.Builder(ctx, CH_TRACK)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle("Registrando actividad")
        .setContentText("GPS activo")
        .setOngoing(true)
        .build()

    /** Resumen del día. Rojo si sube de peso, verde si baja. */
    @SuppressLint("MissingPermission")
    fun summary(ctx: Context, s: DaySummary) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val gain = s.gramsChange > 0
        val color = if (gain) Color.RED else Color.rgb(0, 140, 60)
        val title = "Resumen de hoy: " +
            if (gain) "has subido ~${Calc.num(s.gramsChange)} g" else "has bajado ~${Calc.num(-s.gramsChange)} g"
        val body = "Pasos: ${Calc.num(s.steps.toDouble())} · Distancia: ${Calc.num(s.meters / 1000, 1)} km · " +
            "Activo: ${s.activeSeconds / 60} min\n" +
            "Ingeridas: ${Calc.num(s.intakeKcal)} kcal · Gastadas: ${Calc.num(s.bmrKcal + s.activityKcal)} kcal · " +
            "Saldo: ${Calc.num(s.balanceKcal)} kcal"

        fun colored(text: String) = SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color), 0, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val n = NotificationCompat.Builder(ctx, CH_SUMMARY)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle(colored(title))
            .setContentText(colored(body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(colored(body)))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_SUMMARY, n)
    }
}
