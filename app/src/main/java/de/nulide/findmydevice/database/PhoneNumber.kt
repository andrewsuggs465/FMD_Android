package de.nulide.findmydevice.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import de.nulide.findmydevice.commands.FmdPermission

@Entity(
    tableName = "phone_number",
    indices = [
        Index(value = ["number"], unique = true),
    ],
)
data class PhoneNumber(
    @PrimaryKey(autoGenerate = true) val rowId: Int = 0,

    @ColumnInfo("name") val name: String,

    @ColumnInfo("number") val number: String,

    @ColumnInfo("permissions") val permission: Long = FmdPermission.ALL,
) : AccessItem {

    override fun getItemPermission(): Long {
        return permission
    }

    override fun toDisplayLabel(): String {
        return "$name ($number)"
    }
}
