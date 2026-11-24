package com.calorias.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** DISTANCE: por km. TIME: por tiempo. MIXED: tiempo cuando está parado + distancia cuando se mueve. */
enum class Mode { DISTANCE, TIME, MIXED }

/**
 * Tipo de actividad creado por el usuario.
 * factor = gasto NETO extra por kg y por km (DISTANCE) o por kg y por hora (TIME y MIXED parado).
 * factorDistance = solo MIXED: gasto NETO extra por kg y por km cuando hay movimiento.
 */
@Entity(tableName = "activity_types")
data class ActivityType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val mode: Mode,
    val factor: Double,
    val factorDistance: Double? = null,
    /** Guardar la ruta GPS (un punto por minuto) mientras esta actividad está activa. Solo DISTANCE y MIXED. */
    val saveRoute: Boolean? = null,
    val isBike: Boolean = false,
    val isRest: Boolean = false,
    val isDefault: Boolean = false
)

/** Una jornada (desde "iniciar día" hasta "finalizar día"). */
@Entity(tableName = "days")
data class DayRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val startedAt: Long,
    val endedAt: Long? = null
)

/** Tiempo y distancia acumulados por cada actividad dentro de una jornada. */
@Entity(tableName = "day_activity", primaryKeys = ["dayId", "typeId"])
data class DayActivity(
    val dayId: Long,
    val typeId: Long,
    val seconds: Long = 0,
    val meters: Double = 0.0,
    /** Solo MIXED: segundos en los que no hubo movimiento. */
    val staticSeconds: Long? = null
)

/** Comida registrada en una jornada (copia de nombre, icono y kcal en el momento de añadirla). */
@Entity(tableName = "food")
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val name: String,
    val kcal: Double,
    val time: Long,
    val emoji: String? = null
)

/** Ruta de una actividad con km. Se abre al empezar la actividad y se cierra al cambiar de actividad. */
@Entity(tableName = "routes")
data class RouteRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val typeId: Long,
    val typeName: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val seconds: Long = 0,
    val meters: Double = 0.0,
    val kcal: Double = 0.0
)

/** Punto GPS de una ruta, uno por minuto. */
@Entity(tableName = "route_points")
data class RoutePoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val lat: Double,
    val lng: Double,
    val time: Long
)

/** Catálogo de alimentos reutilizables. */
@Entity(tableName = "food_catalog")
data class FoodItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val kcal: Double
)

@Dao
interface AppDao {
    @Query("SELECT * FROM activity_types ORDER BY id")
    fun observeTypes(): Flow<List<ActivityType>>

    @Query("SELECT * FROM activity_types ORDER BY id")
    suspend fun types(): List<ActivityType>

    @Insert
    suspend fun insertType(t: ActivityType): Long

    @Delete
    suspend fun deleteType(t: ActivityType)

    @Update
    suspend fun updateType(t: ActivityType)

    @Query("UPDATE activity_types SET isDefault = 0")
    suspend fun clearDefault()

    @Insert
    suspend fun insertDay(d: DayRecord): Long

    @Update
    suspend fun updateDay(d: DayRecord)

    @Query("SELECT * FROM days WHERE endedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openDay(): DayRecord?

    @Query("SELECT * FROM days WHERE endedAt IS NULL ORDER BY id DESC LIMIT 1")
    fun observeOpenDay(): Flow<DayRecord?>

    @Query("SELECT * FROM days WHERE date = :date ORDER BY id DESC LIMIT 1")
    suspend fun lastDayOf(date: String): DayRecord?

    @Query("SELECT * FROM days WHERE endedAt IS NOT NULL ORDER BY id DESC LIMIT 1")
    suspend fun lastClosedDay(): DayRecord?

    @Query("SELECT * FROM days ORDER BY id DESC")
    fun observeDays(): Flow<List<DayRecord>>

    @Query(
        "INSERT INTO day_activity (dayId, typeId, seconds, meters, staticSeconds) " +
            "VALUES (:dayId, :typeId, :seconds, :meters, :staticSeconds) " +
            "ON CONFLICT(dayId, typeId) DO UPDATE SET " +
            "seconds = seconds + :seconds, meters = meters + :meters, " +
            "staticSeconds = COALESCE(staticSeconds, 0) + :staticSeconds"
    )
    suspend fun addActivity(dayId: Long, typeId: Long, seconds: Long, meters: Double, staticSeconds: Long)

    @Query("SELECT * FROM day_activity WHERE dayId = :dayId")
    suspend fun dayActivities(dayId: Long): List<DayActivity>

    @Query("SELECT * FROM day_activity WHERE dayId = :dayId")
    fun observeDayActivities(dayId: Long): Flow<List<DayActivity>>

    @Insert
    suspend fun insertFood(f: FoodEntry)

    @Delete
    suspend fun deleteFood(f: FoodEntry)

    @Query("SELECT * FROM food WHERE dayId = :dayId ORDER BY time")
    suspend fun food(dayId: Long): List<FoodEntry>

    @Query("SELECT * FROM food WHERE dayId = :dayId ORDER BY time")
    fun observeFood(dayId: Long): Flow<List<FoodEntry>>

    @Query("SELECT * FROM food_catalog ORDER BY name")
    fun observeCatalog(): Flow<List<FoodItem>>

    @Insert
    suspend fun insertCatalog(f: FoodItem): Long

    @Update
    suspend fun updateCatalog(f: FoodItem)

    @Delete
    suspend fun deleteCatalog(f: FoodItem)

    @Insert
    suspend fun insertRoute(r: RouteRecord): Long

    @Query("SELECT * FROM routes WHERE endedAt IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openRoute(): RouteRecord?

    @Query("UPDATE routes SET endedAt = :now WHERE endedAt IS NULL")
    suspend fun closeOpenRoutes(now: Long)

    @Query("UPDATE routes SET seconds = seconds + :seconds, meters = meters + :meters, kcal = kcal + :kcal WHERE id = :routeId")
    suspend fun addRouteStats(routeId: Long, seconds: Long, meters: Double, kcal: Double)

    @Query("SELECT * FROM routes ORDER BY id DESC")
    fun observeRoutes(): Flow<List<RouteRecord>>

    @Query("SELECT * FROM routes WHERE id = :id")
    fun observeRoute(id: Long): Flow<RouteRecord?>

    @Insert
    suspend fun insertPoint(p: RoutePoint)

    @Query("SELECT * FROM route_points WHERE routeId = :routeId ORDER BY time")
    fun observePoints(routeId: Long): Flow<List<RoutePoint>>
}

/** v1 -> v2: actividad mixta, icono en comidas y catálogo de alimentos. Conserva los datos. */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity_types ADD COLUMN factorDistance REAL")
        db.execSQL("ALTER TABLE day_activity ADD COLUMN staticSeconds INTEGER")
        db.execSQL("ALTER TABLE food ADD COLUMN emoji TEXT")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `food_catalog` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`name` TEXT NOT NULL, `emoji` TEXT NOT NULL, `kcal` REAL NOT NULL)"
        )
    }
}

/** Comidas de serie: nombre, emoticono y kcal por ración (estimación). */
private val SEED_FOODS = listOf(
    Triple("Paella", "🥘", 520.0),
    Triple("Potaje de lentejas", "🍲", 380.0),
    Triple("Arroz a la cubana con huevo y salchichas", "🍚", 650.0),
    Triple("Guisado de patatas y carne", "🍖", 600.0),
    Triple("Alitas de pollo y patatas", "🍗", 800.0),
    Triple("Trucha al horno", "🐟", 320.0),
    Triple("Té con leche", "🍵", 100.0)
)

/** Inserta las comidas de serie sin duplicar las que ya existan con el mismo nombre. */
private fun seedFoods(db: SupportSQLiteDatabase) {
    SEED_FOODS.forEach { (name, emoji, kcal) ->
        db.execSQL(
            "INSERT INTO food_catalog (name, emoji, kcal) SELECT '$name', '$emoji', $kcal " +
                "WHERE NOT EXISTS (SELECT 1 FROM food_catalog WHERE name = '$name')"
        )
    }
}

/** v3 -> v4: comidas de serie en el catálogo. */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        seedFoods(db)
    }
}

/** v2 -> v3: opción de guardar rutas y tablas de rutas y puntos GPS. */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activity_types ADD COLUMN saveRoute INTEGER")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `routes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`dayId` INTEGER NOT NULL, `typeId` INTEGER NOT NULL, `typeName` TEXT NOT NULL, " +
                "`startedAt` INTEGER NOT NULL, `endedAt` INTEGER, `seconds` INTEGER NOT NULL, " +
                "`meters` REAL NOT NULL, `kcal` REAL NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `route_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`routeId` INTEGER NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `time` INTEGER NOT NULL)"
        )
    }
}

@Database(
    entities = [
        ActivityType::class, DayRecord::class, DayActivity::class, FoodEntry::class,
        FoodItem::class, RouteRecord::class, RoutePoint::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "calorias.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Actividades por defecto. Los usuarios pueden añadir más desde Ajustes.
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Descanso','😴','TIME',0.0,0,1,0)")
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Andar','🚶','DISTANCE',0.5,0,0,1)")
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Bicicleta','🚴','DISTANCE',0.3,1,0,0)")
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Trabajo sentado','💻','TIME',0.5,0,0,0)")
                        seedFoods(db)
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
