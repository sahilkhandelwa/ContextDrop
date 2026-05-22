package com.example.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "capsules")
data class Capsule(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val aiName: String, // "ChatGPT", "Claude", "Gemini"
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface CapsuleDao {
    @Query("SELECT * FROM capsules ORDER BY timestamp DESC")
    fun getAllCapsules(): Flow<List<Capsule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCapsule(capsule: Capsule)

    @Query("DELETE FROM capsules WHERE id = :id")
    suspend fun deleteCapsuleById(id: Int)
}

@Database(entities = [Capsule::class], version = 1, exportSchema = false)
abstract class CapsuleDatabase : RoomDatabase() {
    abstract fun capsuleDao(): CapsuleDao

    companion object {
        @Volatile
        private var INSTANCE: CapsuleDatabase? = null

        fun getDatabase(context: Context): CapsuleDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    CapsuleDatabase::class.java,
                    "capsule_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class CapsuleRepository(private val capsuleDao: CapsuleDao) {
    val allCapsules: Flow<List<Capsule>> = capsuleDao.getAllCapsules()

    suspend fun insert(capsule: Capsule) {
        capsuleDao.insertCapsule(capsule)
    }

    suspend fun deleteById(id: Int) {
        capsuleDao.deleteCapsuleById(id)
    }
}
