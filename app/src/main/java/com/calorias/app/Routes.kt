package com.calorias.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
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

/** Mapa de Google con el recorrido de una ruta. La clave de la API va en el manifiesto (MAPS_API_KEY). */
@Composable
fun RouteMapScreen(routeId: Long, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val route by remember(routeId) { dao.observeRoute(routeId) }.collectAsState(initial = null)
    val points by remember(routeId) { dao.observePoints(routeId) }.collectAsState(initial = emptyList())
    val latLngs = remember(points) { points.map { LatLng(it.lat, it.lng) } }

    // La cámara solo se puede encuadrar cuando el mapa ya tiene tamaño (onMapLoaded).
    // Antes de eso, un move() falla en silencio y el recorrido queda fuera de la vista.
    var mapLoaded by remember { mutableStateOf(false) }
    val camera = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(0.0, 0.0), 2f)
    }

    LaunchedEffect(mapLoaded, latLngs) {
        if (!mapLoaded || latLngs.isEmpty()) return@LaunchedEffect
        runCatching {
            if (latLngs.size == 1) {
                camera.move(CameraUpdateFactory.newLatLngZoom(latLngs[0], 16f))
            } else {
                val bounds = LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
                camera.move(CameraUpdateFactory.newLatLngBounds(bounds, 80))
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
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
                Text(
                    "Puntos GPS guardados: ${latLngs.size}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        GoogleMap(
            modifier = Modifier.fillMaxWidth().weight(1f),
            cameraPositionState = camera,
            onMapLoaded = { mapLoaded = true }
        ) {
            if (latLngs.size >= 2) Polyline(points = latLngs)
            latLngs.firstOrNull()?.let { Marker(state = MarkerState(position = it), title = "Inicio") }
            if (latLngs.size >= 2) Marker(state = MarkerState(position = latLngs.last()), title = "Fin")
        }
    }
}
