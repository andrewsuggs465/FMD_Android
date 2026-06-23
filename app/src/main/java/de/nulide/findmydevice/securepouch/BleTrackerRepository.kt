package de.nulide.findmydevice.securepouch

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.android.volley.Request
import com.android.volley.Response
import com.android.volley.VolleyError
import de.nulide.findmydevice.data.FmdKeyPair
import de.nulide.findmydevice.data.FmdLocation
import de.nulide.findmydevice.data.Settings
import de.nulide.findmydevice.data.SettingsRepository
import de.nulide.findmydevice.net.JsonObjectRequest
import de.nulide.findmydevice.net.JsonPostRequest
import de.nulide.findmydevice.utils.CypherUtils
import de.nulide.findmydevice.utils.PatchedVolley
import de.nulide.findmydevice.utils.log
import org.json.JSONObject
import java.security.PublicKey
import java.util.Date

/**
 * Stores credentials for each paired SecurePouch device and handles location posting.
 *
 * Each pouch has its own FMD server account. The key used here is the BLE DEVICE_ID
 * characteristic value (= the username passed to registerPouch), which must match
 * the value flashed into the firmware as DEVICE_UID.
 *
 * Credentials are stored in EncryptedSharedPreferences (device-bound AES256).
 */
class BleTrackerRepository(private val context: Context) {

    companion object {
        private const val TAG = "BleTrackerRepository"
        private const val PREFS_FILE = "sp_ble_tracker"
        private const val KEY_ALL_UIDS = "all_uids"
        // Per-pouch keys (prefixed by bleUid)
        private fun kServerId(uid: String) = "${uid}_server_device_id"
        private fun kHashedPw(uid: String) = "${uid}_hashed_pw"
        private fun kToken(uid: String) = "${uid}_access_token"
        private fun kPubKey(uid: String) = "${uid}_public_key"
        private fun kLastSeen(uid: String) = "${uid}_last_seen_ms"
        private fun kLastRssi(uid: String) = "${uid}_last_rssi"
        private fun kPending(uid: String) = "${uid}_pending_commands"

        // SecurePouch BLE control opcodes — keep in sync with
        // firmware/shared/ble_protocol.h (SP_CTRL_*) and the SP_CMD_* strings.
        const val SP_CHAR_CONTROL_UUID = "af19b3e4-d279-4a2a-9d3f-2f5e8a6bc054"

        /** Map an FMD command string to its BLE control opcode, or null if unknown. */
        fun commandToOpcode(command: String): Byte? = when (command) {
            "lock" -> 0x01
            "unlock" -> 0x02
            "arm" -> 0x03
            "disarm" -> 0x04
            "alarm" -> 0x05
            "silence" -> 0x06
            "locate" -> 0x07
            else -> null
        }
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val queue by lazy { PatchedVolley.newRequestQueue(context) }
    private val settingsRepo by lazy { SettingsRepository.getInstance(context) }

    // ---------- Query ----------

    fun hasPouches(): Boolean = getPouchUids().isNotEmpty()

    fun getPouchUids(): Set<String> =
        prefs.getStringSet(KEY_ALL_UIDS, emptySet()) ?: emptySet()

    fun getPublicKey(bleUid: String): PublicKey? {
        val b64 = prefs.getString(kPubKey(bleUid), null) ?: return null
        return CypherUtils.decodeRsaPublicKey(b64)
    }

    fun getAccessToken(bleUid: String): String? =
        prefs.getString(kToken(bleUid), null)

    fun getLastSeen(bleUid: String): Long? =
        prefs.getLong(kLastSeen(bleUid), -1L).takeIf { it >= 0 }

    fun getLastRssi(bleUid: String): Int? =
        prefs.getInt(kLastRssi(bleUid), Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    fun storeLastRssi(bleUid: String, rssi: Int) {
        prefs.edit().putInt(kLastRssi(bleUid), rssi).apply()
    }

    fun removePouch(bleUid: String) {
        val all = (prefs.getStringSet(KEY_ALL_UIDS, emptySet()) ?: emptySet()).toMutableSet()
        all.remove(bleUid)
        prefs.edit()
            .putStringSet(KEY_ALL_UIDS, all)
            .remove(kServerId(bleUid))
            .remove(kHashedPw(bleUid))
            .remove(kToken(bleUid))
            .remove(kPubKey(bleUid))
            .remove(kLastSeen(bleUid))
            .remove(kLastRssi(bleUid))
            .remove(kPending(bleUid))
            .apply()
    }

    // ---------- Registration ----------

    /**
     * Register a new pouch account on the FMD server, then cache the access token.
     *
     * MUST NOT be called on the main thread — Argon2 hashing is CPU-intensive.
     *
     * @param bleUid             The DEVICE_ID value in the firmware (= the FMD server username)
     * @param password           A secret password chosen for this pouch's server account
     * @param registrationToken  The server's registration token (from vault)
     */
    fun registerPouch(
        bleUid: String,
        password: String,
        registrationToken: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val baseUrl = serverBaseUrl()

        // CPU-intensive — must not be on main thread
        val keys = FmdKeyPair.generateNewFmdKeyPair(password)
        val hashedPw = CypherUtils.hashPasswordForLogin(password)

        val body = JSONObject().apply {
            put("hashedPassword", hashedPw)
            put("pubkey", keys.base64PublicKey)
            put("privkey", keys.encryptedPrivateKey)
            put("requestedUsername", bleUid)
            put("registrationToken", registrationToken)
        }

        val request = JsonObjectRequest(
            Request.Method.PUT, "$baseUrl/device", body,
            Response.Listener { response ->
                val serverDeviceId = response.getString("DeviceId")
                fetchAndStoreToken(bleUid, serverDeviceId, hashedPw, keys.base64PublicKey, baseUrl, onSuccess, onError)
            },
            Response.ErrorListener { error ->
                val detail = errorText(error)
                if (detail.contains("Failed to create username", ignoreCase = true)) {
                    // Account already exists on the server (e.g. registered from another
                    // phone or a previous install) — try logging in with the given password.
                    context.log().i(TAG, "Account '$bleUid' exists, attempting login instead")
                    loginPouch(bleUid, password, onSuccess) { loginErr ->
                        onError("Device '$bleUid' is already registered, and login failed: $loginErr — wrong password?")
                    }
                } else {
                    onError("Registration failed: $detail")
                }
            },
        )
        queue.add(request)
    }

    /**
     * Log in to an existing pouch account on the FMD server and cache its credentials.
     * Used when the device ID is already registered (e.g. after app reinstall).
     */
    fun loginPouch(
        bleUid: String,
        password: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val baseUrl = serverBaseUrl()

        val saltBody = JSONObject().apply {
            put("IDT", bleUid)
            put("Data", "")
        }
        val saltRequest = JsonObjectRequest(
            Request.Method.PUT, "$baseUrl/salt", saltBody,
            Response.Listener { saltResponse ->
                val salt = saltResponse.getString("Data")
                // Volley callbacks run on the main thread; Argon2 must not
                Thread {
                    val hashedPw = CypherUtils.hashPasswordForLogin(password, salt)
                    fetchTokenAndPubKey(bleUid, hashedPw, baseUrl, onSuccess, onError)
                }.start()
            },
            Response.ErrorListener { error ->
                onError("Salt fetch failed: ${errorText(error)}")
            },
        )
        queue.add(saltRequest)
    }

    private fun fetchTokenAndPubKey(
        bleUid: String,
        hashedPw: String,
        baseUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val body = JSONObject().apply {
            put("IDT", bleUid)
            put("Data", hashedPw)
            put("SessionDurationSeconds", 7 * 24 * 60 * 60)
        }
        val request = JsonObjectRequest(
            Request.Method.PUT, "$baseUrl/requestAccess", body,
            Response.Listener { response ->
                val token = response.getString("Data")
                fetchPubKey(bleUid, hashedPw, token, baseUrl, onSuccess, onError)
            },
            Response.ErrorListener { error ->
                onError("Login failed: ${errorText(error)}")
            },
        )
        queue.add(request)
    }

    private fun fetchPubKey(
        bleUid: String,
        hashedPw: String,
        token: String,
        baseUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val body = JSONObject().apply {
            put("IDT", token)
            put("Data", "")
        }
        val request = JsonObjectRequest(
            Request.Method.PUT, "$baseUrl/pubKey", body,
            Response.Listener { response ->
                val pubKeyBase64 = response.getString("Data")
                saveCredentials(bleUid, bleUid, hashedPw, token, pubKeyBase64)
                context.log().i(TAG, "Logged in to existing account for $bleUid")
                onSuccess()
            },
            Response.ErrorListener { error ->
                onError("Public key fetch failed: ${errorText(error)}")
            },
        )
        queue.add(request)
    }

    /** Extract a human-readable message from a Volley error (HTTP status + server body). */
    private fun errorText(error: VolleyError): String {
        val net = error.networkResponse
        if (net != null) {
            val body = net.data?.toString(Charsets.UTF_8)?.trim()?.take(200) ?: ""
            return "HTTP ${net.statusCode}${if (body.isNotEmpty()) ": $body" else ""}"
        }
        return error.message ?: error.javaClass.simpleName
    }

    private fun fetchAndStoreToken(
        bleUid: String,
        serverDeviceId: String,
        hashedPw: String,
        publicKeyBase64: String,
        baseUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
    ) {
        val body = JSONObject().apply {
            put("IDT", serverDeviceId)
            put("Data", hashedPw)
            put("SessionDurationSeconds", 7 * 24 * 60 * 60)
        }

        val request = JsonObjectRequest(
            Request.Method.PUT, "$baseUrl/requestAccess", body,
            Response.Listener { response ->
                val token = response.getString("Data")
                saveCredentials(bleUid, serverDeviceId, hashedPw, token, publicKeyBase64)
                context.log().i(TAG, "Registered and stored credentials for $bleUid")
                onSuccess()
            },
            Response.ErrorListener { error ->
                onError("Token fetch failed: ${error.message}")
            },
        )
        queue.add(request)
    }

    private fun saveCredentials(
        bleUid: String,
        serverDeviceId: String,
        hashedPw: String,
        accessToken: String,
        publicKeyBase64: String,
    ) {
        prefs.edit()
            .putString(kServerId(bleUid), serverDeviceId)
            .putString(kHashedPw(bleUid), hashedPw)
            .putString(kToken(bleUid), accessToken)
            .putString(kPubKey(bleUid), publicKeyBase64)
            .apply()

        val all = (prefs.getStringSet(KEY_ALL_UIDS, emptySet()) ?: emptySet()).toMutableSet()
        all.add(bleUid)
        prefs.edit().putStringSet(KEY_ALL_UIDS, all).apply()
    }

    // ---------- Token refresh ----------

    /**
     * Re-run requestAccess using the stored hashed password and update the cached token.
     * The [onRefreshed] callback fires on the main thread with the new token on success,
     * or [onFail] fires if the server rejects the credentials (password changed / account
     * deleted — user must re-pair in that case).
     */
    private fun refreshToken(
        bleUid: String,
        onRefreshed: (newToken: String) -> Unit,
        onFail: () -> Unit,
    ) {
        val hashedPw = prefs.getString(kHashedPw(bleUid), null) ?: run {
            context.log().e(TAG, "No stored password for '$bleUid' — cannot refresh token")
            onFail()
            return
        }
        val baseUrl = serverBaseUrl()
        val body = JSONObject().apply {
            put("IDT", bleUid)
            put("Data", hashedPw)
            put("SessionDurationSeconds", 7 * 24 * 60 * 60)
        }
        val request = JsonObjectRequest(
            Request.Method.PUT, "$baseUrl/requestAccess", body,
            Response.Listener { response ->
                val newToken = response.getString("Data")
                prefs.edit().putString(kToken(bleUid), newToken).apply()
                context.log().i(TAG, "Token refreshed for '$bleUid'")
                onRefreshed(newToken)
            },
            Response.ErrorListener { error ->
                context.log().e(TAG, "Token refresh failed for '$bleUid': ${errorText(error)}")
                onFail()
            },
        )
        queue.add(request)
    }

    // ---------- Location posting ----------

    /**
     * Encrypt [location] with the pouch's RSA public key and POST it to the FMD server.
     * [rssi] (dBm) is included in the encrypted payload for the dashboard distance ring.
     * On a 401 the token is refreshed once automatically and the post retried.
     */
    fun postLocation(bleUid: String, location: FmdLocation, rssi: Int? = null) {
        val accessToken = getAccessToken(bleUid)
        val publicKey = getPublicKey(bleUid)

        if (accessToken == null || publicKey == null) {
            context.log().e(TAG, "No credentials for '$bleUid' — register the pouch first")
            return
        }

        val locationJson = JSONObject().apply {
            put("provider", location.provider)
            put("lat", location.lat)
            put("lon", location.lon)
            put("accuracy", location.accuracy)
            put("altitude", location.altitude)
            put("heading", location.bearing)
            put("speed", location.speed)
            put("bat", location.batteryLevel)
            put("date", location.timeMillis)
            put("time", Date(location.timeMillis).toString())
            rssi?.let { put("rssi", it) }
        }

        val encryptedBytes = CypherUtils.encryptWithKey(publicKey, locationJson.toString())
        val encryptedBase64 = CypherUtils.encodeBase64(encryptedBytes)

        doPostLocation(bleUid, accessToken, encryptedBase64, retried = false)
    }

    private fun doPostLocation(bleUid: String, token: String, encryptedBase64: String, retried: Boolean) {
        val body = JSONObject().apply {
            put("IDT", token)
            put("Data", encryptedBase64)
        }
        val request = JsonPostRequest(
            Request.Method.POST, "${serverBaseUrl()}/location", body,
            Response.Listener { _ ->
                prefs.edit().putLong(kLastSeen(bleUid), System.currentTimeMillis()).apply()
                context.log().i(TAG, "Location posted for pouch '$bleUid'")
            },
            Response.ErrorListener { error ->
                val status = error.networkResponse?.statusCode
                if (status == 401 && !retried) {
                    context.log().w(TAG, "Location post 401 for '$bleUid' — refreshing token")
                    refreshToken(bleUid,
                        onRefreshed = { newToken -> doPostLocation(bleUid, newToken, encryptedBase64, retried = true) },
                        onFail = { context.log().e(TAG, "Token refresh failed — location dropped for '$bleUid'") },
                    )
                } else {
                    context.log().e(TAG, "Location post failed for '$bleUid': ${errorText(error)}")
                }
            },
        )
        queue.add(request)
    }

    // ---------- Commands (pouch control) ----------

    /**
     * Poll the FMD server for a pending command for this pouch. The command
     * channel is single-delivery: the server clears it once read. The result is
     * delivered to [onCommand] (empty string => nothing pending) so the caller
     * can relay it to the pouch over BLE.
     */
    fun pollServerCommand(bleUid: String, onCommand: (String) -> Unit) {
        val accessToken = getAccessToken(bleUid) ?: return
        doPollServerCommand(bleUid, accessToken, onCommand, retried = false)
    }

    private fun doPollServerCommand(bleUid: String, token: String, onCommand: (String) -> Unit, retried: Boolean) {
        val body = JSONObject().apply {
            put("IDT", token)
            put("Data", "")
        }
        val request = JsonObjectRequest(
            Request.Method.PUT, "${serverBaseUrl()}/command", body,
            Response.Listener { response ->
                val cmd = response.optString("Data", "").trim()
                if (cmd.isNotEmpty()) {
                    context.log().i(TAG, "Server command for '$bleUid': $cmd")
                    onCommand(cmd)
                }
            },
            Response.ErrorListener { error ->
                val status = error.networkResponse?.statusCode
                if (status == 401 && !retried) {
                    context.log().w(TAG, "Command poll 401 for '$bleUid' — refreshing token")
                    refreshToken(bleUid,
                        onRefreshed = { newToken -> doPollServerCommand(bleUid, newToken, onCommand, retried = true) },
                        onFail = { context.log().e(TAG, "Token refresh failed — command poll skipped for '$bleUid'") },
                    )
                } else {
                    context.log().w(TAG, "Command poll failed for '$bleUid': ${errorText(error)}")
                }
            },
        )
        queue.add(request)
    }

    /**
     * Locally queue a command for a pouch (e.g. from the control UI). The scan
     * service drains this queue and relays it over BLE next time it sees the
     * pouch — same delivery path as a server command. Stored as a small set so
     * multiple distinct commands survive until the pouch is next in range.
     */
    fun queueLocalCommand(bleUid: String, command: String) {
        val pending = (prefs.getStringSet(kPending(bleUid), emptySet()) ?: emptySet()).toMutableSet()
        pending.add(command)
        prefs.edit().putStringSet(kPending(bleUid), pending).apply()
    }

    /** Atomically take and clear any locally-queued commands for a pouch. */
    fun drainLocalCommands(bleUid: String): Set<String> {
        val pending = prefs.getStringSet(kPending(bleUid), emptySet()) ?: emptySet()
        if (pending.isNotEmpty()) {
            prefs.edit().remove(kPending(bleUid)).apply()
        }
        return pending
    }

    // ---------- Helpers ----------

    private fun serverBaseUrl(): String =
        (settingsRepo.get(Settings.SET_FMDSERVER_URL) as String).trimEnd('/') + "/api/v1"
}
