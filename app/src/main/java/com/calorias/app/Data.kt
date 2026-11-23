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
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

enum class Mode { DISTANCE, TIME }

/**
 * Tipo de actividad creado por el usuario.
 * factor = gasto NETO extra (por encima del basal) por kg y por km (DISTANCE)
 *          o por kg y por hora (TIME). Al crearla se convierte desde kcal/km o kcal/h.
 */
@Entity(tableName = "activity_types")
data class ActivityType(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    val mode: Mode,
    val factor: Double,
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
    val meters: Double = 0.0
)

@Entity(tableName = "food")
data class FoodEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val name: String,
    val kcal: Double,
    val time: Long
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

    @Query("SELECT * FROM days ORDER BY id DESC")
    fun observeDays(): Flow<List<DayRecord>>

    @Query(
        "INSERT INTO day_activity (dayId, typeId, seconds, meters) VALUES (:dayId, :typeId, :seconds, :meters) " +
            "ON CONFLICT(dayId, typeId) DO UPDATE SET seconds = seconds + :seconds, meters = meters + :meters"
    )
    suspend fun addActivity(dayId: Long, typeId: Long, seconds: Long, meters: Double)

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
}

@Database(
    entities = [ActivityType::class, DayRecord::class, DayActivity::class, FoodEntry::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile
        private var instance: AppDb? = null

        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "calorias.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        // Actividades por defecto. Los usuarios pueden añadir más desde Ajustes.
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Descanso','😴','TIME',0.0,0,1,0)")
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Andar','🚶','DISTANCE',0.5,0,0,1)")
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Bicicleta','🚴','DISTANCE',0.3,1,0,0)")
                        db.execSQL("INSERT INTO activity_types (name, emoji, mode, factor, isBike, isRest, isDefault) VALUES ('Trabajo sentado','💻','TIME',0.5,0,0,0)")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
