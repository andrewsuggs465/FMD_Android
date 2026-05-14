package de.nulide.findmydevice.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PhoneNumberDao {
    @Query("SELECT * FROM phone_number")
    fun getAll(): Flow<List<PhoneNumber>>

    @Query("SELECT * FROM phone_number WHERE number = :number LIMIT 1")
    suspend fun get(number: String): PhoneNumber?

    @Insert
    suspend fun insert(number: PhoneNumber)

    @Update
    suspend fun update(number: PhoneNumber)

    @Delete
    suspend fun delete(number: PhoneNumber)
}
