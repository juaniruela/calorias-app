package com.calorias.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

/** Lista de rutas guardadas, de la más reciente a la más antigua. */
@Composable
fun RoutesList(onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val routes by dao.observeRoutes().collectAsState(initial = emptyList())

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Rutas", style = MaterialTheme.typography.titleLarge)
        if (routes.isEmpty()) {
            Text("Aún no hay rutas. Activa «Guardar rutas» en una actividad por km.")
        }
        routes.forEach { r ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(r.id) }) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${r.typeName} · ${fechaHora(r.startedAt)}", style = MaterialTheme.typography.titleSmall)
                    Text("Fin: ${r.endedAt?.let { fechaHora(it) } ?: "en curso"}")
                    Text(resumenRuta(r))
                }
            }
        }
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

/** Descarga la imagen. Devuelve la imagen o, si falla, el mensaje de error de Google. */
private suspend fun fetchStaticMap(url: String): Pair<Bitmap?, String?> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (conn.responseCode == 200) {
            BitmapFactory.decodeStream(conn.inputStream) to null
        } else {
            val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            null to "Google no devolvió el mapa (código ${conn.responseCode}). ${body.take(300)}"
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
    LaunchedEffect(url) {
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
            error != null -> Text(error, color = Color(0xFFC62828), modifier = Modifier.padding(16.dp))
            else -> Text("Cargando mapa…", modifier = Modifier.padding(16.dp))
        }
    }
}
