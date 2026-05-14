package de.nulide.findmydevice.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsPasswordDao {
    @Query("SELECT * FROM sms_password")
    fun getAll(): Flow<List<SmsPassword>>

    @Query("SELECT * FROM sms_password WHERE password = :password LIMIT 1")
    suspend fun get(password: String): SmsPassword?

    @Insert
    suspend fun insert(password: SmsPassword)

    @Update
    suspend fun update(password: SmsPassword)

    @Delete
    suspend fun delete(password: SmsPassword)
}
