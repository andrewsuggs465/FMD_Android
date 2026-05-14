package de.nulide.findmydevice.data

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonReader
import de.nulide.findmydevice.R
import de.nulide.findmydevice.utils.Notifications
import de.nulide.findmydevice.utils.Notifications.CHANNEL_FAILED
import de.nulide.findmydevice.utils.SingletonHolder
import de.nulide.findmydevice.utils.log
import java.io.File
import java.io.FileReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.LinkedList


const val ALLOWLIST_FILENAME = "whitelist.json"

data class Contact(
    val name: String,
    val number: String,
)

@Keep
class AllowlistModel : LinkedList<Contact>()


class AllowlistRepository private constructor(private val context: Context) {

    companion object : SingletonHolder<AllowlistRepository, Context>(::AllowlistRepository) {
        val TAG = AllowlistRepository::class.simpleName
    }

    private val gson = Gson()

    suspend fun migrateAllowlist() {
        val file = File(context.filesDir, ALLOWLIST_FILENAME)
        if (!file.exists()) {
            // nothing to migrate
            return
        }
        context.log().i(TAG, "Migrating whitelist.json to access.db")
        val oldList = loadListFromJsonFile(file)
        val accessRepo = AccessRepository.getInstance(context)
        accessRepo.migratePhoneAllowListToDb(oldList)
        file.delete()
    }

    private fun loadListFromJsonFile(file: File): AllowlistModel {
        val reader = JsonReader(FileReader(file))
        return try {
            gson.fromJson(reader, AllowlistModel::class.java) ?: AllowlistModel()
        } catch (e: JsonSyntaxException) {
            context.log().e(TAG, e.stackTraceToString())
            // Reset the list
            notifyAllowlistReset()
            AllowlistModel()
        }
    }

    // Note: This method is kept to support importing whitelist.json files from old backups.
    @Throws(JsonIOException::class, JsonSyntaxException::class)
    suspend fun importFromStreamToDb(inputStream: InputStream) {
        val reader = JsonReader(InputStreamReader(inputStream))
        val list = gson.fromJson(reader, AllowlistModel::class.java) ?: AllowlistModel()

        val accessRepo = AccessRepository.getInstance(context)
        accessRepo.migratePhoneAllowListToDb(list)
    }

    private fun notifyAllowlistReset() {
        val title = context.getString(R.string.allowlist_reset_title)
        val text = context.getString(R.string.allowlist_reset_text)
        Notifications.notify(context, title, text, CHANNEL_FAILED)
    }
}
