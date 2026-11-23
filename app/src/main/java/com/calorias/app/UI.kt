package com.calorias.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GOOD = Color(0xFF2E7D32)
private val BAD = Color(0xFFC62828)

private fun String.toDec(): Double? = replace(',', '.').toDoubleOrNull()

// ---------------------------------------------------------------- HOY

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val store = remember { SettingsStore(ctx) }

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(5_000)
        }
    }
    val settings = remember(now) { store.load() }
    val types by dao.observeTypes().collectAsState(initial = emptyList())
    val open by dao.observeOpenDay().collectAsState(initial = null)
    val dayId = open?.id ?: -1L
    val acts by remember(dayId) { dao.observeDayActivities(dayId) }.collectAsState(initial = emptyList())
    val food by remember(dayId) { dao.observeFood(dayId) }.collectAsState(initial = emptyList())

    var activeId by remember { mutableLongStateOf(store.activeTypeId) }
    LaunchedEffect(now) { activeId = store.activeTypeId }
    var showFood by remember { mutableStateOf(false) }

    val summary = open?.let { Calc.summarize(settings, it, types, acts, food, now) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Calorías", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = {
                scope.launch {
                    if (open == null) DayController.startDay(ctx) else DayController.endDay(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (open == null) "▶  Iniciar día" else "■  Finalizar día")
        }

        if (open == null || summary == null) {
            Text(
                "Pulsa «Iniciar día» para empezar. El GPS registra la posición cada " +
                    "${settings.gpsIntervalSec} s y la actividad por defecto es andar."
            )
        } else {
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text("💡 Consejo de hoy", style = MaterialTheme.typography.titleSmall)
                    Text(Calc.tip(settings))
                }
            }

            StatsCard(summary)

            Text("Actividad actual", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.forEach { t ->
                    FilterChip(
                        selected = t.id == activeId,
                        onClick = {
                            activeId = t.id
                            store.activeTypeId = t.id
                            scope.launch { DayController.refreshWidget(ctx) }
                        },
                        label = { Text("${t.emoji} ${t.name}") }
                    )
                }
            }

            summary.byType.forEach { line ->
                Text(
                    "${line.type.emoji} ${line.type.name}: " +
                        "${Calc.num(line.seconds / 60.0)} min · " +
                        "${Calc.num(line.meters / 1000, 2)} km · " +
                        "${Calc.num(line.kcal)} kcal"
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Comidas", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showFood = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Añadir")
                }
            }
            food.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${Calc.hhmm(f.time)}  ${f.name}: ${Calc.num(f.kcal)} kcal", modifier = Modifier.weight(1f))
                    IconButton(onClick = { scope.launch { dao.deleteFood(f) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Borrar")
                    }
                }
            }
        }
    }

    val current = open
    if (showFood && current != null) {
        FoodDialog(
            onDismiss = { showFood = false },
            onAdd = { name, kcal ->
                scope.launch {
                    dao.insertFood(FoodEntry(dayId = current.id, name = name, kcal = kcal, time = System.currentTimeMillis()))
                }
                showFood = false
            }
        )
    }
}

@Composable
private fun StatsCard(s: DaySummary) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Pasos", Calc.num(s.steps.toDouble()))
            StatRow("Distancia", "${Calc.num(s.meters / 1000, 2)} km")
            StatRow("Tiempo activo", "${s.activeSeconds / 60} min")
            StatRow("Comida ingerida", "${Calc.num(s.intakeKcal)} kcal")
            StatRow("Gasto (basal + actividad)", "${Calc.num(s.bmrKcal + s.activityKcal)} kcal")
            StatRow(
                "Saldo", "${Calc.num(s.balanceKcal)} kcal",
                if (s.balanceKcal > 0) BAD else GOOD
            )
            StatRow(
                "Estimación de peso", Calc.gramsText(s.gramsChange),
                if (s.gramsChange > 0) BAD else GOOD
            )
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = color)
    }
}

@Composable
private fun FoodDialog(onDismiss: () -> Unit, onAdd: (String, Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var kcal by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir comida") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Menú / alimento") }, singleLine = true
                )
                OutlinedTextField(
                    value = kcal, onValueChange = { kcal = it },
                    label = { Text("Kcal") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                kcal.toDec()?.let { onAdd(name.ifBlank { "Comida" }, it) }
            }) { Text("Añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---------------------------------------------------------------- HISTORIAL

@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val days by dao.observeDays().collectAsState(initial = emptyList())

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Historial", style = MaterialTheme.typography.headlineMedium)
        if (days.isEmpty()) Text("Aún no hay días registrados.")
        days.forEach { day -> DayHistoryCard(day) }
    }
}

@Composable
private fun DayHistoryCard(day: DayRecord) {
    val ctx = LocalContext.current
    val sum by produceState<DaySummary?>(null, day.id, day.endedAt) {
        value = DayController.summaryOf(ctx, day)
    }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "${day.date} · ${Calc.hhmm(day.startedAt)} – " +
                    (day.endedAt?.let { Calc.hhmm(it) } ?: "en curso"),
                style = MaterialTheme.typography.titleSmall
            )
            sum?.let { s ->
                Text("Pasos ${Calc.num(s.steps.toDouble())} · ${Calc.num(s.meters / 1000, 2)} km · ${s.activeSeconds / 60} min")
                Text("Ingeridas ${Calc.num(s.intakeKcal)} kcal · Gastadas ${Calc.num(s.bmrKcal + s.activityKcal)} kcal")
                Text(
                    "Saldo ${Calc.num(s.balanceKcal)} kcal · ${Calc.gramsText(s.gramsChange)}",
                    color = if (s.gramsChange > 0) BAD else GOOD
                )
            }
        }
    }
}

// ---------------------------------------------------------------- AJUSTES

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(ctx) }
    val dao = remember { AppDb.get(ctx).dao() }
    val types by dao.observeTypes().collectAsState(initial = emptyList())

    val s0 = remember { store.load() }
    var weight by remember { mutableStateOf(s0.weightKg.toString()) }
    var height by remember { mutableStateOf(s0.heightCm.toString()) }
    var age by remember { mutableStateOf(s0.age.toString()) }
    var male by remember { mutableStateOf(s0.male) }
    var stride by remember { mutableStateOf(s0.strideM.toString()) }
    var kcalFat by remember { mutableStateOf(s0.kcalPerKgFat.toString()) }
    var gps by remember { mutableStateOf(s0.gpsIntervalSec.toString()) }
    var fast by remember { mutableStateOf(s0.fastKmh.toString()) }
    var veryFast by remember { mutableStateOf(s0.veryFastKmh.toString()) }
    var window by remember { mutableStateOf(s0.speedWindowMin.toString()) }
    var sHour by remember { mutableStateOf(s0.summaryHour.toString()) }
    var sMin by remember { mutableStateOf(s0.summaryMinute.toString()) }
    var tips by remember { mutableStateOf(s0.tips) }
    var showNew by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val weightNow = weight.toDec() ?: s0.weightKg

    fun save() {
        val s = Settings(
            weightKg = weight.toDec() ?: s0.weightKg,
            heightCm = height.toDec() ?: s0.heightCm,
            age = age.toIntOrNull() ?: s0.age,
            male = male,
            strideM = stride.toDec() ?: s0.strideM,
            kcalPerKgFat = kcalFat.toDec() ?: s0.kcalPerKgFat,
            gpsIntervalSec = gps.toIntOrNull()?.coerceAtLeast(5) ?: s0.gpsIntervalSec,
            fastKmh = fast.toDec() ?: s0.fastKmh,
            veryFastKmh = veryFast.toDec() ?: s0.veryFastKmh,
            speedWindowMin = window.toIntOrNull()?.coerceAtLeast(1) ?: s0.speedWindowMin,
            summaryHour = sHour.toIntOrNull()?.coerceIn(0, 23) ?: s0.summaryHour,
            summaryMinute = sMin.toIntOrNull()?.coerceIn(0, 59) ?: s0.summaryMinute,
            tips = tips
        )
        store.save(s)
        SummaryWorker.schedule(ctx, s.summaryHour, s.summaryMinute)
        scope.launch { DayController.refreshWidget(ctx) }
        saved = true
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineMedium)

        Text("Datos personales", style = MaterialTheme.typography.titleMedium)
        Field("Peso (kg)", weight) { weight = it; saved = false }
        Field("Altura (cm)", height) { height = it; saved = false }
        Field("Edad", age) { age = it; saved = false }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hombre", Modifier.weight(1f))
            Switch(checked = male, onCheckedChange = { male = it; saved = false })
        }

        Text("Pasos y GPS", style = MaterialTheme.typography.titleMedium)
        Field("Longitud de paso (m)", stride) { stride = it; saved = false }
        Field("Intervalo GPS (s, aplica al iniciar el día)", gps) { gps = it; saved = false }

        Text("Reglas de velocidad", style = MaterialTheme.typography.titleMedium)
        Field("Solo bicicleta por encima de (km/h)", fast) { fast = it; saved = false }
        Field("Ni bicicleta por encima de (km/h)", veryFast) { veryFast = it; saved = false }
        Field("Tiempo sostenido (min)", window) { window = it; saved = false }

        Text("Energía", style = MaterialTheme.typography.titleMedium)
        Field("Kcal de grasa por kg (para la estimación de peso)", kcalFat) { kcalFat = it; saved = false }

        Text("Resumen diario", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Hora", sHour, Modifier.weight(1f)) { sHour = it; saved = false }
            Field("Minuto", sMin, Modifier.weight(1f)) { sMin = it; saved = false }
        }

        Text("Consejos (uno por línea)", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = tips, onValueChange = { tips = it; saved = false },
            modifier = Modifier.fillMaxWidth(), minLines = 3
        )

        Button(onClick = { save() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (saved) "Ajustes guardados ✓" else "Guardar ajustes")
        }

        Text("Actividades", style = MaterialTheme.typography.titleMedium)
        types.forEach { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val perUnit = if (t.mode == Mode.DISTANCE) "kcal/km" else "kcal/h"
                val shown = t.factor * weightNow
                Text(
                    "${t.emoji} ${t.name} · ${Calc.num(shown, 2)} $perUnit" +
                        (if (t.isBike) " · bici" else "") +
                        (if (t.isRest) " · descanso" else ""),
                    Modifier.weight(1f)
                )
                IconButton(onClick = { scope.launch { dao.deleteType(t) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar")
                }
            }
        }
        Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Nueva actividad")
        }
    }

    if (showNew) {
        NewActivityDialog(
            weightKg = weightNow,
            onDismiss = { showNew = false },
            onCreate = { type ->
                scope.launch {
                    if (type.isDefault) dao.clearDefault()
                    dao.insertType(type)
                }
                showNew = false
            }
        )
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    )
}

/**
 * Crea una actividad. Transformación: se introduce kcal extra por km (distancia)
 * o por hora (tiempo) y se guarda dividido por el peso, para que siga siendo
 * válido si cambia el peso del usuario.
 */
@Composable
private fun NewActivityDialog(
    weightKg: Double,
    onDismiss: () -> Unit,
    onCreate: (ActivityType) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🏃") }
    var byDistance by remember { mutableStateOf(true) }
    var value by remember { mutableStateOf("") }
    var bike by remember { mutableStateOf(false) }
    var makeDefault by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nueva actividad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(emoji, { emoji = it }, label = { Text("Icono (emoji)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = byDistance, onClick = { byDistance = true },
                        label = { Text("Por km") }
                    )
                    FilterChip(
                        selected = !byDistance, onClick = { byDistance = false },
                        label = { Text("Por tiempo") }
                    )
                }
                OutlinedTextField(
                    value, { value = it },
                    label = { Text(if (byDistance) "Kcal extra por km" else "Kcal extra por hora") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                val v = value.toDec()
                if (v != null && weightKg > 0) {
                    Text(
                        "Se guarda como ${Calc.num(v / weightKg, 3)} kcal por kg " +
                            (if (byDistance) "y km" else "y hora"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bike, onCheckedChange = { bike = it })
                    Text("Es bicicleta (permitida > velocidad de bici)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = makeDefault, onCheckedChange = { makeDefault = it })
                    Text("Actividad por defecto al iniciar el día")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v2 = value.toDec() ?: return@TextButton
                if (name.isBlank()) return@TextButton
                onCreate(
                    ActivityType(
                        name = name.trim(),
                        emoji = emoji.ifBlank { "🏃" },
                        mode = if (byDistance) Mode.DISTANCE else Mode.TIME,
                        factor = if (weightKg > 0) v2 / weightKg else 0.0,
                        isBike = bike,
                        isDefault = makeDefault
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
