package com.calorias.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val GOOD = Color(0xFF2E7D32)
internal val BAD = Color(0xFFC62828)

private fun String.toDec(): Double? = replace(',', '.').toDoubleOrNull()

private fun fmt(x: Double): String = String.format(java.util.Locale.US, "%.2f", x)

private fun describe(t: ActivityType, w: Double): String = when (t.mode) {
    Mode.DISTANCE -> "${Calc.num(t.factor * w, 2)} kcal/km"
    Mode.TIME -> "${Calc.num(t.factor * w, 2)} kcal/h"
    Mode.MIXED -> "${Calc.num(t.factor * w, 2)} kcal/h parado + ${Calc.num((t.factorDistance ?: 0.0) * w, 2)} kcal/km"
}

// ---------------------------------------------------------------- HOY

@OptIn(ExperimentalLayoutApi::class)
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
    val foods by dao.observeCatalog().collectAsState(initial = emptyList())
    val open by dao.observeOpenDay().collectAsState(initial = null)
    val dayId = open?.id ?: -1L
    val acts by remember(dayId) { dao.observeDayActivities(dayId) }.collectAsState(initial = emptyList())
    val meals by remember(dayId) { dao.observeFood(dayId) }.collectAsState(initial = emptyList())

    var activeId by remember { mutableLongStateOf(store.activeTypeId) }
    LaunchedEffect(now) { activeId = store.activeTypeId }
    var showMeal by remember { mutableStateOf(false) }

    val summary = open?.let { Calc.summarize(settings, it, types, acts, meals, now) }

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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                types.forEach { t ->
                    FilterChip(
                        selected = t.id == activeId,
                        onClick = {
                            activeId = t.id
                            store.activeTypeId = t.id
                            // Reaplica el GPS: se apaga en actividades por tiempo y se enciende en las demás.
                            if (open != null) TrackingService.start(ctx)
                            scope.launch { DayController.refreshWidget(ctx) }
                        },
                        label = { Text("${t.emoji} ${t.name}") }
                    )
                }
            }

            summary.byType.filter { it.seconds > 0 || it.meters > 0 }.forEach { line ->
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
                TextButton(onClick = { showMeal = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("Añadir")
                }
            }
            meals.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${Calc.hhmm(f.time)}  ${f.emoji ?: "🍽️"} ${f.name}: ${Calc.num(f.kcal)} kcal",
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { scope.launch { dao.deleteFood(f) } }) {
                        Icon(Icons.Default.Delete, contentDescription = "Borrar")
                    }
                }
            }
        }
    }

    val current = open
    if (showMeal && current != null) {
        MealDialog(
            foods = foods,
            onDismiss = { showMeal = false },
            onPick = { food ->
                scope.launch {
                    dao.insertFood(
                        FoodEntry(
                            dayId = current.id, name = food.name, kcal = food.kcal,
                            time = System.currentTimeMillis(), emoji = food.emoji
                        )
                    )
                }
                showMeal = false
            },
            onCreate = { name, kcal, emoji ->
                scope.launch {
                    dao.insertCatalog(FoodItem(name = name, emoji = emoji, kcal = kcal))
                    dao.insertFood(
                        FoodEntry(
                            dayId = current.id, name = name, kcal = kcal,
                            time = System.currentTimeMillis(), emoji = emoji
                        )
                    )
                }
                showMeal = false
            }
        )
    }
}

@Composable
internal fun StatsCard(s: DaySummary) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Pasos", Calc.num(s.steps.toDouble()))
            StatRow("Distancia", "${Calc.num(s.meters / 1000, 2)} km")
            StatRow("Tiempo activo", "${s.activeSeconds / 60} min")
            StatRow("Velocidad media", "${Calc.num(s.avgKmh, 1)} km/h")
            StatRow("Comida ingerida", "${Calc.num(s.intakeKcal)} kcal (${s.meals} comidas)")
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
internal fun StatRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = color)
    }
}

/**
 * Añadir comida: se elige del catálogo. Si el nombre no existe, se crea el alimento
 * en el catálogo al añadir la comida del día.
 */
@Composable
internal fun MealDialog(
    foods: List<FoodItem>,
    onDismiss: () -> Unit,
    onPick: (FoodItem) -> Unit,
    onCreate: (String, Double, String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var kcalText by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("🍽️") }
    val q = query.trim()
    val matches = foods.filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
    val exact = foods.firstOrNull { it.name.equals(q, ignoreCase = true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Añadir comida") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("Buscar o nombre nuevo") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                matches.forEach { f ->
                    Text(
                        "${f.emoji} ${f.name} · ${Calc.num(f.kcal)} kcal",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(f) }
                            .padding(vertical = 8.dp)
                    )
                }
                if (exact == null && q.isNotEmpty()) {
                    Text("Nuevo alimento: elige icono y kcal", style = MaterialTheme.typography.bodySmall)
                    EmojiGrid(selected = emoji) { emoji = it }
                    OutlinedTextField(
                        value = kcalText, onValueChange = { kcalText = it },
                        label = { Text("Kcal") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (exact != null) {
                    onPick(exact)
                } else {
                    val k = kcalText.toDec() ?: return@TextButton
                    if (q.isEmpty()) return@TextButton
                    onCreate(q, k, emoji)
                }
            }) { Text(if (exact != null) "Añadir" else "Crear y añadir") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Cuadrícula de emoticonos; la seleccionada se resalta. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EmojiGrid(selected: String, onPick: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .height(170.dp)
            .verticalScroll(rememberScrollState())
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            EMOJIS.forEach { e ->
                Text(
                    e,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .background(
                            if (e == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onPick(e) }
                        .padding(6.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------- HISTORIAL


// ---------------------------------------------------------------- AJUSTES

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SettingsStore(ctx) }
    val dao = remember { AppDb.get(ctx).dao() }
    val types by dao.observeTypes().collectAsState(initial = emptyList())
    val foods by dao.observeCatalog().collectAsState(initial = emptyList())

    val s0 = remember { store.load() }
    var weight by remember { mutableStateOf(s0.weightKg.toString()) }
    var height by remember { mutableStateOf(s0.heightCm.toString()) }
    var age by remember { mutableStateOf(s0.age.toString()) }
    var male by remember { mutableStateOf(s0.male) }
    var stride by remember { mutableStateOf(s0.strideM.toString()) }
    var kcalFat by remember { mutableStateOf(s0.kcalPerKgFat.toString()) }
    var gps by remember { mutableStateOf(s0.gpsIntervalSec.toString()) }
    var routePoint by remember { mutableStateOf(s0.routePointSec.toString()) }
    var fast by remember { mutableStateOf(s0.fastKmh.toString()) }
    var veryFast by remember { mutableStateOf(s0.veryFastKmh.toString()) }
    var window by remember { mutableStateOf(s0.speedWindowMin.toString()) }
    var moveThr by remember { mutableStateOf(s0.moveThresholdM.toString()) }
    var sHour by remember { mutableStateOf(s0.summaryHour.toString()) }
    var sMin by remember { mutableStateOf(s0.summaryMinute.toString()) }
    var tips by remember { mutableStateOf(s0.tips) }
    var mapsKey by remember { mutableStateOf(s0.mapsApiKey) }
    var showNew by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ActivityType?>(null) }
    var showNewFood by remember { mutableStateOf(false) }
    var editingFood by remember { mutableStateOf<FoodItem?>(null) }
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
            routePointSec = routePoint.toIntOrNull()?.coerceAtLeast(5) ?: s0.routePointSec,
            fastKmh = fast.toDec() ?: s0.fastKmh,
            veryFastKmh = veryFast.toDec() ?: s0.veryFastKmh,
            speedWindowMin = window.toIntOrNull()?.coerceAtLeast(1) ?: s0.speedWindowMin,
            moveThresholdM = moveThr.toDec() ?: s0.moveThresholdM,
            summaryHour = sHour.toIntOrNull()?.coerceIn(0, 23) ?: s0.summaryHour,
            summaryMinute = sMin.toIntOrNull()?.coerceIn(0, 59) ?: s0.summaryMinute,
            tips = tips,
            mapsApiKey = mapsKey.trim()
        )
        store.save(s)
        SummaryWorker.schedule(ctx, s.summaryHour, s.summaryMinute)
        scope.launch {
            DayController.refreshWidget(ctx)
            // Si hay un día abierto, el servicio aplica ya el nuevo intervalo GPS.
            if (dao.openDay() != null) TrackingService.start(ctx)
        }
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
        Field("Intervalo de lectura GPS (s)", gps) { gps = it; saved = false }
        Field("Intervalo de puntos de la ruta (s)", routePoint) { routePoint = it; saved = false }
        Field("Movimiento mínimo por lectura (m, actividad mixta)", moveThr) { moveThr = it; saved = false }

        Text("Google Maps", style = MaterialTheme.typography.titleMedium)
        Text(
            "Clave de la Maps Static API para dibujar las rutas. Se guarda solo en este móvil.",
            style = MaterialTheme.typography.bodySmall
        )
        Field("Clave de Google Maps (MAPS_API_KEY)", mapsKey) { mapsKey = it; saved = false }

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
                Text(
                    "${t.emoji} ${t.name} · ${describe(t, weightNow)}" +
                        (if (t.isBike) " · bici" else "") +
                        (if (t.isRest) " · descanso" else "") +
                        (if (t.isDefault) " · por defecto" else ""),
                    Modifier.weight(1f)
                )
                TextButton(onClick = { editing = t }) { Text("Editar") }
                IconButton(onClick = {
                    scope.launch {
                        dao.deleteType(t)
                        if (dao.openDay() != null) TrackingService.start(ctx)
                    }
                }) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar")
                }
            }
        }
        Button(onClick = { showNew = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Nueva actividad")
        }

        Text("Alimentos", style = MaterialTheme.typography.titleMedium)
        foods.forEach { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${f.emoji} ${f.name} · ${Calc.num(f.kcal)} kcal", Modifier.weight(1f))
                TextButton(onClick = { editingFood = f }) { Text("Editar") }
                IconButton(onClick = { scope.launch { dao.deleteCatalog(f) } }) {
                    Icon(Icons.Default.Delete, contentDescription = "Borrar")
                }
            }
        }
        Button(onClick = { showNewFood = true }, modifier = Modifier.fillMaxWidth()) {
            Text("+ Nuevo alimento")
        }
    }

    if (showNew || editing != null) {
        ActivityDialog(
            weightKg = weightNow,
            initial = editing,
            onDismiss = { showNew = false; editing = null },
            onSave = { type ->
                scope.launch {
                    if (type.isDefault) dao.clearDefault()
                    if (type.id == 0L) dao.insertType(type) else dao.updateType(type)
                    // Si la actividad activa cambió de modo (p. ej. a «Por tiempo»), el GPS se reajusta.
                    if (dao.openDay() != null) TrackingService.start(ctx)
                }
                showNew = false
                editing = null
            }
        )
    }

    if (showNewFood || editingFood != null) {
        FoodItemDialog(
            initial = editingFood,
            onDismiss = { showNewFood = false; editingFood = null },
            onSave = { food ->
                scope.launch {
                    if (food.id == 0L) dao.insertCatalog(food) else dao.updateCatalog(food)
                }
                showNewFood = false
                editingFood = null
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

/** Alimento del catálogo: nombre, icono y kcal. */
@Composable
private fun FoodItemDialog(
    initial: FoodItem?,
    onDismiss: () -> Unit,
    onSave: (FoodItem) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🍽️") }
    var kcal by remember { mutableStateOf(initial?.let { fmt(it.kcal) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo alimento" else "Editar alimento") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(
                    kcal, { kcal = it }, label = { Text("Kcal por ración") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text("Icono: $emoji")
                EmojiGrid(selected = emoji) { emoji = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = kcal.toDec() ?: return@TextButton
                if (name.isBlank()) return@TextButton
                onSave(FoodItem(id = initial?.id ?: 0L, name = name.trim(), emoji = emoji, kcal = k))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/**
 * Crea o edita una actividad. Transformación: se introduce kcal extra por km (distancia)
 * o por hora (tiempo) y se guarda dividido por el peso, para que siga siendo
 * válido si cambia el peso del usuario.
 * Mixta: kcal por hora parado + kcal por km en movimiento.
 */
@Composable
private fun ActivityDialog(
    weightKg: Double,
    initial: ActivityType?,
    onDismiss: () -> Unit,
    onSave: (ActivityType) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var emoji by remember { mutableStateOf(initial?.emoji ?: "🏃") }
    var mode by remember { mutableStateOf(initial?.mode ?: Mode.DISTANCE) }
    var value by remember { mutableStateOf(initial?.let { fmt(it.factor * weightKg) } ?: "") }
    var valueDist by remember {
        mutableStateOf(initial?.factorDistance?.let { fmt(it * weightKg) } ?: "")
    }
    var bike by remember { mutableStateOf(initial?.isBike ?: false) }
    var makeDefault by remember { mutableStateOf(initial?.isDefault ?: false) }
    var saveRoute by remember { mutableStateOf(initial?.saveRoute ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nueva actividad" else "Editar actividad") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                Text("Icono: $emoji")
                EmojiGrid(selected = emoji) { emoji = it }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == Mode.DISTANCE, onClick = { mode = Mode.DISTANCE },
                        label = { Text("Por km") }
                    )
                    FilterChip(
                        selected = mode == Mode.TIME, onClick = { mode = Mode.TIME },
                        label = { Text("Por tiempo") }
                    )
                    FilterChip(
                        selected = mode == Mode.MIXED, onClick = { mode = Mode.MIXED },
                        label = { Text("Mixta") }
                    )
                }

                if (mode == Mode.MIXED) {
                    Text(
                        "Mixta: si no te mueves cuenta por tiempo; si te mueves, por distancia.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value, { value = it },
                        label = { Text("Kcal extra por hora (parado)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        valueDist, { valueDist = it },
                        label = { Text("Kcal extra por km (en movimiento)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                } else {
                    OutlinedTextField(
                        value, { value = it },
                        label = { Text(if (mode == Mode.DISTANCE) "Kcal extra por km" else "Kcal extra por hora") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }

                if (mode != Mode.TIME) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = saveRoute, onCheckedChange = { saveRoute = it })
                        Text("Guardar rutas (GPS cada minuto, se cierra al cambiar de actividad)")
                    }
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
                val v = value.toDec() ?: return@TextButton
                if (name.isBlank()) return@TextButton
                val vd = if (mode == Mode.MIXED) (valueDist.toDec() ?: return@TextButton) else null
                onSave(
                    ActivityType(
                        id = initial?.id ?: 0L,
                        name = name.trim(),
                        emoji = emoji.ifBlank { "🏃" },
                        mode = mode,
                        factor = if (weightKg > 0) v / weightKg else 0.0,
                        factorDistance = if (vd != null && weightKg > 0) vd / weightKg else null,
                        isBike = bike,
                        isRest = initial?.isRest ?: false,
                        isDefault = makeDefault,
                        saveRoute = mode != Mode.TIME && saveRoute
                    )
                )
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
