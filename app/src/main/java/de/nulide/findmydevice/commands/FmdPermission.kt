package de.nulide.findmydevice.commands

/**
 * Bitmask for encoding fine-grained, per-command permissions into a Long.
 * This allows us to store a single Long in the database to persist the permissions.
 */
enum class FmdPermission(val bit: Long) {

    // In practice, "help" should always be allowed.
    // But the implementation is simpler if we have a bitmask value for it.
    // Changing this should not be offered by the UI.
    HELP(1L shl 0),

    BLUETOOTH(1L shl 1),
    CAMERA(1L shl 2),
    DELETE(1L shl 3),
    FLASH(1L shl 4),
    GPS(1L shl 5),
    LOCATE(1L shl 6),
    LOCK(1L shl 7),
    NO_DISTURB(1L shl 8),
    RING(1L shl 9),
    RINGER_MODE(1L shl 10),
    STATS(1L shl 11),
    ;

    companion object {
        val ALL: Long = entries.fold(0L) { acc, p -> acc or p.bit }
    }
}

fun Long.hasPermission(p: FmdPermission): Boolean {
    return this and p.bit != 0L
}

fun Long.setPermission(p: FmdPermission, enabled: Boolean): Long {
    return if (enabled) addPermission(p) else removePermission(p)
}

fun Long.addPermission(p: FmdPermission): Long {
    return this or p.bit
}

fun Long.removePermission(p: FmdPermission): Long {
    return this and p.bit.inv()
}

fun Long.togglePermission(p: FmdPermission): Long {
    return this xor p.bit
}
