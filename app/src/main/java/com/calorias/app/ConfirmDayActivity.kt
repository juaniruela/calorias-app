package com.calorias.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

/** Diálogo de confirmación que se abre desde el widget antes de iniciar o finalizar el día. */
class ConfirmDayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val ctx = LocalContext.current
                val scope = rememberCoroutineScope()
                var running by remember { mutableStateOf<Boolean?>(null) }
                LaunchedEffect(Unit) {
                    running = AppDb.get(ctx).dao().openDay() != null
                }
                val isOn = running
                if (isOn != null) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(if (isOn) "¿Finalizar el día?" else "¿Iniciar el día?") },
                        text = {
                            Text(
                                if (isOn) "Se detendrá el GPS, se cerrará la jornada y se enviará el resumen."
                                else "Empezará el registro con el GPS."
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    if (isOn) DayController.endDay(ctx) else DayController.startDay(ctx)
                                    finish()
                                }
                            }) { Text(if (isOn) "Finalizar" else "Iniciar") }
                        },
                        dismissButton = { TextButton(onClick = { finish() }) { Text("Cancelar") } }
                    )
                }
            }
        }
    }
}
