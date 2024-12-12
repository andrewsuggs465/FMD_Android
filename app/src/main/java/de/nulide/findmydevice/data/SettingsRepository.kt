package de.nulide.findmydevice.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import com.google.gson.ToNumberStrategy
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import com.google.gson.stream.MalformedJsonException
import de.nulide.findmydevice.R
import de.nulide.findmydevice.utils.CypherUtils
import de.nulide.findmydevice.utils.SingletonHolder
import de.nulide.findmydevice.utils.writeToUri
import java.io.File
import java.io.FileNotFoundException
import java.io.FileReader
import java.io.FileWriter
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.security.KeyFactory
import java.security.NoSuchAlgorithmException
import java.security.PublicKey
import java.security.spec.EncodedKeySpec
import java.security.spec.InvalidKeySpecException
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


const val SETTINGS_FILENAME = "settings.json"


// Workaround for Gson defaulting to Long or Double instead of Int.
// The underlying problem is that Settings is not a strongly typed map (it uses Object/Any)
//
// Inspired by/copied from ToNumberPolicy.LONG_OR_DOUBLE.
//
// We cannot use LONG_OR_DOUBLE because sometimes Gson does use Integers, and then our
// code cannot handle both Long and Integer. So just deserialise as Int.
object INT_OR_DOUBLE : ToNumberStrategy {
    @Throws(IOException::class, JsonParseException::class)
    override fun readNumber(`in`: JsonReader): Number {
        val value = `in`.nextString()
        return try {
            value.toInt()
        } catch (e: NumberFormatException) {
            parseAsDouble(value, `in`)
        }
    }

    @Throws(IOException::class)
    private fun parseAsDouble(value: String, `in`: JsonReader): Number {
        try {
            val d = value.toDouble()
            if ((d.isInfinite() || d.isNaN()) && !`in`.isLenient) {
                throw MalformedJsonException(
                    "JSON forbids NaN and infinities: " + d + "; at path " + `in`.previousPath
                )
            }
            return d
        } catch (e: java.lang.NumberFormatException) {
            throw JsonParseException(
                "Cannot parse " + value + "; at path " + `in`.previousPath, e
            )
        }
    }
}


/**
 * Settings should be accessed through this repository.
 * This is to only have a single Settings instance,
 * thus preventing race conditions.
 */
class SettingsRepository private constructor(private val context: Context) {

    companion object :
        SingletonHolder<SettingsRepository, Context>(::SettingsRepository) {

        val TAG = SettingsRepository::class.simpleName

        fun filenameForExport(): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val date = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                return "fmd-settings-$date.json"
            } else {
                return "fmd-settings.json"
            }
        }
    }

    private val gson = GsonBuilder()
        .setObjectToNumberStrategy(INT_OR_DOUBLE) //(ToNumberPolicy.LONG_OR_DOUBLE)
        .create()

    // Should only be accessed via the getters/setters in this repository
    private var settings: Settings

    init {
        settings = loadNoSet()
    }

    fun load() {
        settings = loadNoSet()
    }

    private fun loadNoSet(): Settings {
        val file = File(context.filesDir, SETTINGS_FILENAME)
        if (!file.exists()) {
            file.createNewFile()
        }
        val reader = JsonReader(FileReader(file))
        return gson.fromJson(reader, Settings::class.java) ?: Settings()
    }

    private fun saveSettings() {
        val file = File(context.filesDir, SETTINGS_FILENAME)
        val writer = JsonWriter(FileWriter(file))
        gson.toJson(settings, Settings::class.java, writer)
        writer.close()
    }

    fun <T> set(key: Int, value: T) {
        settings.set(key, value)
        saveSettings()
    }

    fun get(key: Int): Any {
        return settings.get(key)
    }

    fun writeToUri(context: Context, uri: Uri) {
        writeToUri(context, uri, settings)
    }

    fun importFromUri(context: Context, uri: Uri) {
        val inputStream: InputStream
        try {
            inputStream = context.contentResolver.openInputStream(uri) ?: return
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
            Toast.makeText(
                context,
                context.getString(R.string.Settings_Import_Failed),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val reader = JsonReader(InputStreamReader(inputStream))
        settings = gson.fromJson(reader, Settings::class.java) ?: Settings()
        saveSettings()
        inputStream.close()

        Toast.makeText(
            context,
            context.getString(R.string.Settings_Import_Success),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun migrateSettings() {
        // Nothing to do currently
        set(Settings.SET_SET_VERSION, Settings.SETTINGS_VERSION)
    }

// ---------- Convenience helpers ----------

    fun accessViaPinEnabled(): Boolean {
        val enabled = get(Settings.SET_ACCESS_VIA_PIN) as Boolean
        val pinHash = get(Settings.SET_PIN) as String
        return enabled && pinHash.isNotEmpty()
    }

    fun serverAccountExists(): Boolean {
        val id = get(Settings.SET_FMDSERVER_ID) as String
        return id.isNotEmpty()
    }

    fun setKeys(keys: FmdKeyPair) {
        set(Settings.SET_FMD_CRYPT_PRIVKEY, keys.encryptedPrivateKey)
        set(Settings.SET_FMD_CRYPT_PUBKEY, CypherUtils.encodeBase64(keys.publicKey.encoded))
    }

    fun getKeys(): FmdKeyPair? {
        if (get(Settings.SET_FMD_CRYPT_PUBKEY) == "") {
            return null
        }

        val pubKeySpec: EncodedKeySpec = X509EncodedKeySpec(
            CypherUtils.decodeBase64(get(Settings.SET_FMD_CRYPT_PUBKEY) as String)
        )
        var publicKey: PublicKey? = null
        try {
            val keyFactory = KeyFactory.getInstance("RSA")
            publicKey = keyFactory.generatePublic(pubKeySpec)
        } catch (e: NoSuchAlgorithmException) {
            e.printStackTrace()
        } catch (e: InvalidKeySpecException) {
            e.printStackTrace()
        }

        return if (publicKey != null) {
            FmdKeyPair(publicKey, get(Settings.SET_FMD_CRYPT_PRIVKEY) as String)
        } else {
            null
        }
    }

    fun removeServerAccount() {
        set(Settings.SET_FMDSERVER_ID, "");
        set(Settings.SET_FMD_CRYPT_HPW, "");
        set(Settings.SET_FMD_CRYPT_PRIVKEY, "");
        set(Settings.SET_FMD_CRYPT_PUBKEY, "");
    }
}
