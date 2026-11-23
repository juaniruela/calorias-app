package com.calorias.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Busca e instala la última versión publicada en la release apk-latest del repositorio. */
object Updater {
    private const val RELEASE_API = "https://api.github.com/repos/juaniruela/calorias-app/releases/tags/apk-latest"
    private const val APK_NAME = "calorias.apk"

    data class Info(val buildId: String, val apkUrl: String)

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "calorias-app")
        }

    /** Lee la release y el identificador de compilación que el workflow escribe en sus notas. */
    suspend fun latest(): Info? = withContext(Dispatchers.IO) {
        try {
            val conn = open(RELEASE_API).apply { setRequestProperty("Accept", "application/vnd.github+json") }
            if (conn.responseCode != 200) return@withContext null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val buildId = Regex("BUILD_ID=(\\S+)").find(json.optString("body"))?.groupValues?.get(1)
                ?: return@withContext null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == APK_NAME) {
                    return@withContext Info(buildId, a.getString("browser_download_url"))
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    /** Descarga el APK a la caché de la app y devuelve el fichero. */
    suspend fun download(ctx: Context, url: String): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, APK_NAME)
            open(url).inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** Lanza el instalador de Android. Si falta el permiso de «instalar apps desconocidas», abre esa pantalla. */
    fun install(ctx: Context, apk: File): String? {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            val settings = Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES")
                .setData(Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(settings)
            return "Permite instalar apps desconocidas para Calorías y vuelve a pulsar «Instalar»."
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return null
    }
}

@Composable
fun UpdateScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Pulsa «Buscar actualización» para comprobar si hay una versión nueva.") }
    var remote by remember { mutableStateOf<Updater.Info?>(null) }
    var busy by remember { mutableStateOf(false) }
    val installed = BuildConfig.BUILD_ID
    val hasNew = remote != null && remote?.buildId != installed

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        Text("Actualizar", style = MaterialTheme.typography.headlineMedium)
        Text("Versión instalada: build $installed")
        Text(status)

        Button(
            enabled = !busy,
            onClick = {
                busy = true
                status = "Buscando actualización…"
                scope.launch {
                    val r = Updater.latest()
                    remote = r
                    status = when {
                        r == null -> "No se pudo consultar la última versión. Revisa la conexión."
                        r.buildId == installed -> "Ya tienes la última versión."
                        else -> "Hay una versión nueva (build ${r.buildId})."
                    }
                    busy = false
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Buscar actualización") }

        if (hasNew) {
            Button(
                enabled = !busy,
                onClick = {
                    val info = remote ?: return@Button
                    busy = true
                    status = "Descargando la versión nueva…"
                    scope.launch {
                        val apk = Updater.download(ctx, info.apkUrl)
                        status = if (apk == null) {
                            "No se pudo descargar el APK."
                        } else {
                            Updater.install(ctx, apk) ?: "Instalador abierto. Confirma la instalación."
                        }
                        busy = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Descargar e instalar") }
        }
    }
}
