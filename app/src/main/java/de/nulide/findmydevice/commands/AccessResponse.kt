package de.nulide.findmydevice.commands

enum class AccessResponse {
    /**
     * The access is allowed.
     */
    ALLOWED,

    /**
     * The requester is known, but denied access to this specific command.
     */
    DENIED_EXISTS,

    /**
     * The requester is unknown and is therefore denied access.
     */
    DENIED_UNKNOWN,
}
