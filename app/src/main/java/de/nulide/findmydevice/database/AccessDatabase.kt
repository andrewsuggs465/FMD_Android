package de.nulide.findmydevice.database

import androidx.room.Database
import androidx.room.RoomDatabase

const val ACCESS_DB_FILENAME = "access.db"

@Database(
    entities = [
        PhoneNumber::class,
        SmsPassword::class,
        NotificationPassword::class,
    ],
    version = 1,
)
abstract class AccessDatabase : RoomDatabase() {
    abstract fun phoneNumberDao(): PhoneNumberDao

    abstract fun smsPasswordDao(): SmsPasswordDao

    abstract fun notificationPasswordDao(): NotificationPasswordDao
}
