package com.calorias.app

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val UN_DIA_MS = 86_400_000L

/** Pantalla de historial: pestaña de días y pestaña de rutas. */
@Composable
fun HistoryScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val days by dao.observeDays().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    var openRouteId by remember { mutableStateOf<Long?>(null) }

    val routeId = openRouteId
    if (routeId != null) {
        BackHandler { openRouteId = null }
        RouteMapScreen(routeId = routeId) { openRouteId = null }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Historial", style = MaterialTheme.typography.headlineMedium)
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Historial") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Rutas") })
            }
            if (tab == 0) {
                if (days.isEmpty()) Text("Aún no hay días registrados.")
                days.forEach { day -> DayHistoryCard(day) }
            } else {
                RoutesList { openRouteId = it }
            }
        }
    }
}

/** Un día en una línea (fecha, saldo y kg). La flecha despliega el resumen y permite editarlo. */
@Composable
private fun DayHistoryCard(day: DayRecord) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    val settings = remember { SettingsStore(ctx).load() }
    val types by dao.observeTypes().collectAsState(initial = emptyList())
    val foods by dao.observeCatalog().collectAsState(initial = emptyList())
    val acts by remember(day.id) { dao.observeDayActivities(day.id) }.collectAsState(initial = emptyList())
    val meals by remember(day.id) { dao.observeFood(day.id) }.collectAsState(initial = emptyList())
    val sum = Calc.summarize(settings, day, types, acts, meals, System.currentTimeMillis())

    var expanded by remember(day.id) { mutableStateOf(false) }
    var addingMeal by remember(day.id) { mutableStateOf(false) }
    var editingMeal by remember(day.id) { mutableStateOf<FoodEntry?>(null) }
    var editingAct by remember(day.id) { mutableStateOf<DayActivity?>(null) }
    var editingTimes by remember(day.id) { mutableStateOf(false) }
    var confirmDelete by remember(day.id) { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(fechaDia(day.date), style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Saldo ${signo(sum.balanceKcal)} kcal · ${kgText(sum.gramsChange)}",
                        color = if (sum.balanceKcal > 0) BAD else GOOD
                    )
                }
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = if (expanded) "Contraer" else "Desplegar",
                    modifier = Modifier.graphicsLayer { rotationZ = if (expanded) 180f else 0f }
                )
            }

            if (expanded) {
                StatsCard(sum)
                TextButton(onClick = { editingTimes = true }) {
                    Text(
                        "Editar horas: ${Calc.hhmm(day.startedAt)} – " +
                            (day.endedAt?.let { Calc.hhmm(it) } ?: "en curso")
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Comidas", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { addingMeal = true }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Añadir")
                    }
                }
                if (meals.isEmpty()) Text("Sin comidas registradas.")
                meals.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Calc.hhmm(f.time)}  ${f.emoji ?: "🍽️"} ${f.name}: ${Calc.num(f.kcal)} kcal",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editingMeal = f }) { Text("Editar") }
                        IconButton(onClick = { scope.launch { dao.deleteFood(f) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar comida")
                        }
                    }
                }

                Text("Actividades", style = MaterialTheme.typography.titleMedium)
                if (acts.isEmpty()) Text("Sin actividad registrada.")
                acts.forEach { a ->
                    val t = types.firstOrNull { it.id == a.typeId }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${t?.emoji ?: ""} ${t?.name ?: "(borrada)"}: " +
                                "${Calc.num(a.seconds / 60.0)} min · ${Calc.num(a.meters / 1000, 2)} km",
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { editingAct = a }) { Text("Editar") }
                        IconButton(onClick = { scope.launch { dao.deleteActivity(day.id, a.typeId) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Borrar actividad")
                        }
                    }
                }

                TextButton(onClick = { confirmDelete = true }) { Text("Borrar día", color = BAD) }
            }
        }
    }

    if (addingMeal) {
        MealDialog(
            foods = foods,
            onDismiss = { addingMeal = false },
            onPick = { food ->
                addingMeal = false
                scope.launch {
                    dao.insertFood(
                        FoodEntry(
                            dayId = day.id, name = food.name, kcal = food.kcal,
                            time = horaPorDefecto(day), emoji = food.emoji
                        )
                    )
                }
            },
            onCreate = { name, kcal, emoji ->
                addingMeal = false
                scope.launch {
                    dao.insertCatalog(FoodItem(name = name, emoji = emoji, kcal = kcal))
                    dao.insertFood(
                        FoodEntry(
                            dayId = day.id, name = name, kcal = kcal,
                            time = horaPorDefecto(day), emoji = emoji
                        )
                    )
                }
            }
        )
    }

    editingMeal?.let { f ->
        FoodEntryDialog(
            day = day,
            initial = f,
            onDismiss = { editingMeal = null },
            onSave = { updated ->
                editingMeal = null
                scope.launch { dao.updateFood(updated) }
            }
        )
    }

    editingAct?.let { a ->
        ActivityEditDialog(
            typeName = types.firstOrNull { it.id == a.typeId }?.name ?: "Actividad",
            initial = a,
            onDismiss = { editingAct = null },
            onSave = { minutes, km ->
                editingAct = null
                val seconds = (minutes * 60).toLong()
                scope.launch {
                    dao.setActivity(
                        day.id, a.typeId, seconds, km * 1000.0,
                        a.staticSeconds?.coerceAtMost(seconds)
                    )
                }
            }
        )
    }

    if (editingTimes) {
        DayTimesDialog(
            day = day,
            onDismiss = { editingTimes = false },
            onSave = { start, end ->
                editingTimes = false
                scope.launch { dao.updateDay(day.copy(startedAt = start, endedAt = end)) }
            }
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            text = "¿Borrar este día con sus comidas, actividades y rutas? No se puede deshacer.",
            onConfirm = {
                confirmDelete = false
                scope.launch { deleteDayFully(ctx, dao, day) }
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

/** Borra el día y todo lo que cuelga de él (actividades, comidas, rutas y puntos GPS). */
private suspend fun deleteDayFully(ctx: Context, dao: AppDao, day: DayRecord) {
    dao.deletePointsOfDay(day.id)
    dao.deleteRoutesOf(day.id)
    dao.deleteActivitiesOf(day.id)
    dao.deleteFoodOf(day.id)
    dao.deleteDayRow(day.id)
    if (day.endedAt == null) TrackingService.stop(ctx)
    DayController.refreshWidget(ctx)
}

@Composable
private fun FoodEntryDialog(
    day: DayRecord,
    initial: FoodEntry,
    onDismiss: () -> Unit,
    onSave: (FoodEntry) -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var kcal by remember { mutableStateOf(plain(initial.kcal)) }
    var hora by remember { mutableStateOf(Calc.hhmm(initial.time)) }
    var emoji by remember { mutableStateOf(initial.emoji ?: "🍽️") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar comida") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(
                    hora, { hora = it }, label = { Text("Hora (HH:mm)") }, singleLine = true
                )
                OutlinedTextField(
                    kcal, { kcal = it }, label = { Text("Kcal") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Text("Icono: $emoji")
                EmojiGrid(selected = emoji) { emoji = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val k = kcal.replace(',', '.').toDoubleOrNull()
                val t = horaDelDia(hora, day.date)
                if (name.isBlank() || k == null || t == null) return@TextButton
                onSave(initial.copy(name = name.trim(), kcal = k, time = t, emoji = emoji))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun ActivityEditDialog(
    typeName: String,
    initial: DayActivity,
    onDismiss: () -> Unit,
    onSave: (minutes: Double, km: Double) -> Unit
) {
    var minutes by remember { mutableStateOf(plain(initial.seconds / 60.0)) }
    var km by remember { mutableStateOf(plain(initial.meters / 1000.0)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar: $typeName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    minutes, { minutes = it }, label = { Text("Minutos") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    km, { km = it }, label = { Text("Kilómetros") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = minutes.replace(',', '.').toDoubleOrNull()
                val k = km.replace(',', '.').toDoubleOrNull()
                if (m == null || k == null || m < 0 || k < 0) return@TextButton
                onSave(m, k)
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

@Composable
private fun DayTimesDialog(
    day: DayRecord,
    onDismiss: () -> Unit,
    onSave: (start: Long, end: Long?) -> Unit
) {
    var start by remember { mutableStateOf(Calc.hhmm(day.startedAt)) }
    var end by remember { mutableStateOf(day.endedAt?.let { Calc.hhmm(it) } ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Editar horas del día") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(start, { start = it }, label = { Text("Inicio (HH:mm)") }, singleLine = true)
                if (day.endedAt != null) {
                    OutlinedTextField(end, { end = it }, label = { Text("Fin (HH:mm)") }, singleLine = true)
                    Text(
                        "Si el fin es antes que el inicio, se entiende que es del día siguiente.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val s = horaDelDia(start, day.date) ?: return@TextButton
                if (day.endedAt == null) {
                    onSave(s, null)
                } else {
                    val e = horaDelDia(end, day.date)?.let { if (it <= s) it + UN_DIA_MS else it }
                        ?: return@TextButton
                    onSave(s, e)
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Confirmación genérica antes de borrar algo. */
@Composable
internal fun ConfirmDialog(text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Sí, borrar", color = BAD) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

// ---- utilidades ----

private fun fechaDia(date: String): String =
    LocalDate.parse(date).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

private fun signo(x: Double): String = (if (x > 0) "+" else "") + Calc.num(x)

private fun kgText(gramos: Double): String = (if (gramos > 0) "+" else "") + Calc.num(gramos / 1000.0, 2) + " kg"

private fun plain(x: Double): String =
    if (x % 1.0 == 0.0) x.toLong().toString() else String.format(Locale.US, "%.2f", x)

/** Hora HH:mm sobre la fecha del día. */
private fun horaDelDia(texto: String, date: String): Long? = runCatching {
    val lt = LocalTime.parse(texto.trim(), DateTimeFormatter.ofPattern("H:mm"))
    LocalDate.parse(date).atTime(lt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

/** Hora por defecto para una comida añadida: ahora si el día está abierto, o al inicio si ya terminó. */
private fun horaPorDefecto(day: DayRecord): Long =
    if (day.endedAt == null) System.currentTimeMillis() else day.startedAt
