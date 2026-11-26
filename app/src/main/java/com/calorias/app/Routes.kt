package com.calorias.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun fechaHora(ms: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "ES")).format(Date(ms))

private fun resumenRuta(r: RouteRecord): String =
    "${r.seconds / 60} min · ${Calc.num(r.meters / 1000, 2)} km · " +
        "${Calc.num(Calc.avgKmh(r.meters, r.seconds), 1)} km/h media · ${Calc.num(r.kcal)} kcal"

/** Lista de rutas: una línea por ruta; la flecha despliega el detalle. */
@Composable
fun RoutesList(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val routes by dao.observeRoutes().collectAsState(initial = emptyList())

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (routes.isEmpty()) {
            Text("Aún no hay rutas. Activa «Guardar rutas» en una actividad por km.")
        }
        routes.forEach { r -> RouteRow(r, onOpen) }
    }
}

@Composable
private fun RouteRow(r: RouteRecord, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDb.get(ctx).dao() }
    var expanded by remember(r.id) { mutableStateOf(false) }
    var confirm by remember(r.id) { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${fechaHora(r.startedAt)} · ${r.typeName}", style = MaterialTheme.typography.titleSmall)
                    Text("${Calc.num(r.meters / 1000, 2)} km · ${Calc.num(r.kcal)} kcal")
                }
                Text(
                    if (expanded) "▴" else "▾",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
            if (expanded) {
                Text("Inicio: ${fechaHora(r.startedAt)}")
                Text("Fin: ${r.endedAt?.let { fechaHora(it) } ?: "en curso"}")
                Text("${r.seconds / 60} min · ${Calc.num(Calc.avgKmh(r.meters, r.seconds), 1)} km/h media")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onOpen(r.id) }) { Text("Ver mapa") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirm = true }) { Text("Borrar ruta", color = BAD) }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDialog(
            text = "¿Borrar esta ruta y sus puntos GPS?",
            onConfirm = {
                confirm = false
                scope.launch {
                    dao.deletePointsOf(r.id)
                    dao.deleteRouteRow(r.id)
                }
            },
            onDismiss = { confirm = false }
        )
    }
}

/**
 * Codificación de polilínea de Google (formato comprimido para la Maps Static API).
 * https://developers.google.com/maps/documentation/utilities/polylinealgorithm
 */
internal fun encodePolyline(points: List<RoutePoint>): String {
    val sb = StringBuilder()
    var lastLat = 0L
    var lastLng = 0L
    for (p in points) {
        val lat = Math.round(p.lat * 1e5)
        val lng = Math.round(p.lng * 1e5)
        encodeValue(lat - lastLat, sb)
        encodeValue(lng - lastLng, sb)
        lastLat = lat
        lastLng = lng
    }
    return sb.toString()
}

private fun encodeValue(value: Long, sb: StringBuilder) {
    var v = if (value < 0) (value shl 1).inv() else value shl 1
    while (v >= 0x20) {
        sb.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
        v = v shr 5
    }
    sb.append((v + 63).toChar())
}

/** URL de la Maps Static API con el recorrido (máx. ~120 puntos para que la URL no sea demasiado larga). */
internal fun staticMapUrl(points: List<RoutePoint>, key: String, width: Int, height: Int): String {
    fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    val step = (points.size + 119) / 120
    val sampled = points.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
    if (sampled.last() !== points.last()) sampled.add(points.last())

    val sb = StringBuilder("https://maps.googleapis.com/maps/api/staticmap?size=${width}x$height&scale=2")
    if (sampled.size >= 2) {
        sb.append("&path=").append(enc("color:0x2E7D32FF|weight:5|enc:" + encodePolyline(sampled)))
    }
    val first = points.first()
    val last = points.last()
    sb.append("&markers=").append(enc("color:green|label:I|${first.lat},${first.lng}"))
    if (points.size >= 2) {
        sb.append("&markers=").append(enc("color:red|label:F|${last.lat},${last.lng}"))
    }
    sb.append("&key=").append(enc(key))
    return sb.toString()
}

/**
 * Descarga la imagen. Devuelve la imagen o, si falla, un mensaje explicando el motivo.
 * La clave nunca se incluye en los mensajes.
 */
private suspend fun fetchStaticMap(url: String): Pair<Bitmap?, String?> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val code = conn.responseCode
        if (code == 200) {
            val bmp = conn.inputStream.use { BitmapFactory.decodeStream(it) }
            if (bmp != null) bmp to null
            else null to "Google devolvió una respuesta que no es una imagen."
        } else {
            val type = conn.contentType.orEmpty()
            // Solo se muestra el texto si es legible; las imágenes de error no sirven de nada en pantalla.
            val detail = if (type.startsWith("text/") || type.contains("json")) {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty().take(200)
            } else ""
            val motivo = when (code) {
                400 -> "Petición no válida."
                403 -> "Google rechaza la clave. Comprueba que Maps Static API esté activada, que la facturación " +
                    "del proyecto esté activa y que la clave no esté restringida a apps Android (esa restricción " +
                    "no sirve para la Maps Static API)."
                429 -> "Se ha superado el límite de uso de la clave."
                else -> "Error de Google Maps."
            }
            null to "$motivo (código $code). ${detail}".trim()
        }
    } catch (e: Exception) {
        null to "No se pudo descargar el mapa: ${e.message ?: "error de red"}"
    }
}

/** Mapa de la ruta (imagen de Google Maps con el recorrido). La clave se toma de Ajustes. */
@Composable
fun RouteMapScreen(routeId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val route by remember(routeId) { dao.observeRoute(routeId) }.collectAsState(initial = null)
    val points by remember(routeId) { dao.observePoints(routeId) }.collectAsState(initial = emptyList())
    val key = remember { SettingsStore(ctx).load().mapsApiKey }

    val url = remember(points, key) {
        if (key.isBlank() || points.isEmpty()) null else staticMapUrl(points, key, 640, 640)
    }
    var result by remember { mutableStateOf<Pair<Bitmap?, String?>?>(null) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(url, attempt) {
        result = null
        result = url?.let { fetchStaticMap(it) }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Volver") }
            Text(route?.typeName ?: "Ruta", style = MaterialTheme.typography.titleMedium)
        }
        route?.let { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${fechaHora(r.startedAt)} – ${r.endedAt?.let { fechaHora(it) } ?: "en curso"}")
                Text(resumenRuta(r), style = MaterialTheme.typography.bodyMedium)
                Text("Puntos GPS guardados: ${points.size}", style = MaterialTheme.typography.bodySmall)
            }
        }

        val bitmap = result?.first
        val error = result?.second
        when {
            key.isBlank() -> Text(
                "Falta la clave de Google Maps. Escríbela en Ajustes → Google Maps.",
                color = Color(0xFFC62828),
                modifier = Modifier.padding(16.dp)
            )
            points.isEmpty() -> Text(
                "Esta ruta no tiene puntos GPS guardados.",
                modifier = Modifier.padding(16.dp)
            )
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Recorrido en el mapa",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)
            )
            error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, color = Color(0xFFC62828))
                Button(onClick = { attempt++ }) { Text("Reintentar") }
            }
            else -> Text("Cargando mapa…", modifier = Modifier.padding(16.dp))
        }
    }
}
