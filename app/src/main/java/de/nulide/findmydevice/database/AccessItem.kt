package de.nulide.findmydevice.database

interface AccessItem {
    fun getItemPermission(): Long

    fun toDisplayLabel(): String
}
