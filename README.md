# Calorías · app Android

App para registrar pasos, distancia (GPS), actividad, comidas y saldo calórico, con widget de inicio.

## Funcionalidad

- **Iniciar / finalizar día** desde la app o desde el widget (iconos ▶ y ■).
- **GPS cada 30 s** (parametrizable) en servicio de primer plano. Acumula tiempo y distancia en la actividad activa y calcula pasos a partir de la distancia y la longitud de paso.
- **Actividades**: por defecto *Descanso*, *Andar* (por defecto al iniciar el día), *Bicicleta* y *Trabajo sentado*. Se pueden crear nuevas.
  - Por distancia: se introducen kcal extra por km y se convierten a kcal/kg/km.
  - Por tiempo: se introducen kcal extra por hora y se convierten a kcal/kg/hora.
- **Reglas de velocidad** (sostenidas durante el tiempo configurado, 5 min por defecto):
  - \> 20 km/h: solo se permite la actividad marcada como bicicleta; si no lo es, pasa a bicicleta.
  - \> 50 km/h: tampoco se permite bicicleta; pasa a descanso.
- **Comidas**: nombre y kcal introducidas a mano.
- **Saldo** = kcal ingeridas − (metabolismo basal prorrateado + kcal de actividad).
- **Estimación de peso** = saldo / 7.700 kcal por kg. Positivo = sube, negativo = baja.
- **Consejos** rotativos en el widget y en la pantalla de Hoy al iniciar el día.
- **Historial** de jornadas para comparar.
- **Resumen al final del día**: notificación con el resumen. En rojo si has subido de peso, en verde si has bajado. Se envía al finalizar el día manualmente o a la hora configurada (23:00 por defecto), en cuyo caso se cierra la jornada automáticamente si sigue abierta.
- Todos los parámetros (peso, altura, edad, sexo, paso, kcal/kg de grasa, intervalo GPS, umbrales de velocidad, hora del resumen, consejos) son editables en **Ajustes**.

## Compilar

### En GitHub (sin instalar nada)

1. Sube este contenido a un repositorio.
2. El workflow `.github/workflows/build.yml` compila `app-debug.apk` en cada push a `main` o manualmente desde la pestaña **Actions** (*Run workflow*).
3. Descarga el artefacto `calorias-debug-apk` y instálalo en el móvil (habilita «instalar apps desconocidas»).

### En local

Requiere JDK 17, Android SDK 34 y Gradle 8.7:

```bash
gradle :app:assembleDebug
```

## Limitaciones

- Estimación orientativa: el GPS no mide bien en interiores, y las kcal de actividad son valores típicos parametrizables.
- Para que el GPS siga en segundo plano, desactiva la optimización de batería para la app.
- El widget y la app no se han probado en un dispositivo real.
- Si la app no se abre en todo el día, el resumen automático solo se envía si hay una jornada abierta a la hora configurada.
