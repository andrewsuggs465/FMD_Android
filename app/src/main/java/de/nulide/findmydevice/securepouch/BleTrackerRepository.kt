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

    // ---------- Location posting ----------

    /**
     * Encrypt [location] with the pouch's RSA public key and POST it to the FMD server
     * using the pouch's access token.
     */
    fun postLocation(bleUid: String, location: FmdLocation) {
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
        }

        val encryptedBytes = CypherUtils.encryptWithKey(publicKey, locationJson.toString())
        val encryptedBase64 = CypherUtils.encodeBase64(encryptedBytes)

        val body = JSONObject().apply {
            put("IDT", accessToken)
            put("Data", encryptedBase64)
        }

        val request = JsonPostRequest(
            Request.Method.POST, "${serverBaseUrl()}/location", body,
            Response.Listener { _ ->
                prefs.edit().putLong(kLastSeen(bleUid), System.currentTimeMillis()).apply()
                context.log().i(TAG, "Location posted for pouch '$bleUid'")
            },
            Response.ErrorListener { error ->
                context.log().e(TAG, "Location post failed for '$bleUid': ${error.message}")
            },
        )
        queue.add(request)
    }

    // ---------- Helpers ----------

    private fun serverBaseUrl(): String =
        (settingsRepo.get(Settings.SET_FMDSERVER_URL) as String).trimEnd('/') + "/api/v1"
}
