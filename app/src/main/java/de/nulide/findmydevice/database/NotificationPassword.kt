package de.nulide.findmydevice.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.nulide.findmydevice.commands.FmdPermission

@Entity(
    tableName = "notification_password",
    indices = [
        Index(value = ["password"], unique = true),
    ],
)
data class NotificationPassword(
    @PrimaryKey(autoGenerate = true) val rowId: Int = 0,

    @ColumnInfo("label") val label: String,

    @ColumnInfo("password") val password: String,

    @ColumnInfo("permissions") val permission: Long = FmdPermission.ALL,
) : AccessItem {

    override fun getItemPermission(): Long {
        return permission
    }

    override fun toDisplayLabel(): String {
        return label
    }
}
