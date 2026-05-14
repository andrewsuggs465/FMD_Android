package de.nulide.findmydevice.data

import android.content.Context
import androidx.room.Room
import de.nulide.findmydevice.database.ACCESS_DB_FILENAME
import de.nulide.findmydevice.database.AccessDatabase
import de.nulide.findmydevice.database.NotificationPassword
import de.nulide.findmydevice.database.PhoneNumber
import de.nulide.findmydevice.database.SmsPassword
import de.nulide.findmydevice.utils.SingletonHolder
import de.nulide.findmydevice.utils.normalizePhoneNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class AccessRepository private constructor(private val context: Context) {

    companion object : SingletonHolder<AccessRepository, Context>(::AccessRepository) {
        val TAG = AccessRepository::class.simpleName
    }

    private val db = Room.databaseBuilder(
        context.applicationContext,
        AccessDatabase::class.java,
        ACCESS_DB_FILENAME,
    ).build()

    /* ------- Phone numbers ------- */

    fun getPhoneNumbers(): Flow<List<PhoneNumber>> {
        return db.phoneNumberDao().getAll()
    }

    suspend fun getPhoneNumber(phoneNumber: String): PhoneNumber? {
        val number = normalizePhoneNumber(context, phoneNumber) ?: return null
        val entity = db.phoneNumberDao().get(number)
        return entity
    }

    // XXX: Dispatchers.IO appears to be needed. Otherwise, the getPhoneNumbers Flow won't emit the new value.
    suspend fun insertPhoneNumber(phoneNumber: PhoneNumber) = withContext(Dispatchers.IO) {
        val number = normalizePhoneNumber(context, phoneNumber.number) ?: return@withContext
        val norm = phoneNumber.copy(number = number)
        db.phoneNumberDao().insert(norm)
    }

    suspend fun updatePhoneNumber(phoneNumber: PhoneNumber) = withContext(Dispatchers.IO) {
        db.phoneNumberDao().update(phoneNumber)
    }

    suspend fun deletePhoneNumber(phoneNumber: PhoneNumber) = withContext(Dispatchers.IO) {
        db.phoneNumberDao().delete(phoneNumber)
    }

    /* ------- SMS Passwords ------- */

    fun getSmsPasswords(): Flow<List<SmsPassword>> {
        return db.smsPasswordDao().getAll()
    }

    suspend fun getSmsPassword(password: String): SmsPassword? {
        return db.smsPasswordDao().get(password)
    }

    suspend fun insertSmsPassword(password: SmsPassword) = withContext(Dispatchers.IO) {
        db.smsPasswordDao().insert(password)
    }

    suspend fun updateSmsPassword(password: SmsPassword) = withContext(Dispatchers.IO) {
        db.smsPasswordDao().update(password)
    }

    suspend fun deleteSmsPassword(password: SmsPassword) = withContext(Dispatchers.IO) {
        db.smsPasswordDao().delete(password)
    }

    /* ------- Notification Passwords ------- */

    fun getNotificationPasswords(): Flow<List<NotificationPassword>> {
        return db.notificationPasswordDao().getAll()
    }

    suspend fun getNotificationPassword(password: String): NotificationPassword? {
        return db.notificationPasswordDao().get(password)
    }

    suspend fun insertNotificationPassword(password: NotificationPassword) =
        withContext(Dispatchers.IO) {
            db.notificationPasswordDao().insert(password)
        }

    suspend fun updateNotificationPassword(password: NotificationPassword) =
        withContext(Dispatchers.IO) {
            db.notificationPasswordDao().update(password)
        }

    suspend fun deleteNotificationPassword(password: NotificationPassword) =
        withContext(Dispatchers.IO) {
            db.notificationPasswordDao().delete(password)
        }

}
