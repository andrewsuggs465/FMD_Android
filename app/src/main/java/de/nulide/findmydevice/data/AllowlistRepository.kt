package de.nulide.findmydevice.data

import android.content.Context
import android.telephony.PhoneNumberUtils
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import de.nulide.findmydevice.utils.SingletonHolder
import java.io.File
import java.io.FileReader
import java.util.LinkedList


private const val ALLOWLIST_FILENAME = "whitelist.json"


class AllowlistModel : LinkedList<Contact>()


class AllowlistRepository private constructor(private val context: Context) {

    companion object : SingletonHolder<AllowlistRepository, Context>(::AllowlistRepository) {}

    private val gson = Gson()

    val list: AllowlistModel

    init {
        val file = File(context.filesDir, ALLOWLIST_FILENAME)
        if (!file.exists()) {
            file.createNewFile()
        }
        val reader = JsonReader(FileReader(file))
        list = gson.fromJson(reader, AllowlistModel::class.java) ?: AllowlistModel()
    }

    private fun saveList() {
        val copiedList = list.clone()
        val raw = gson.toJson(copiedList)
        val file = File(context.filesDir, ALLOWLIST_FILENAME)
        file.writeText(raw)
    }

    fun contains(c: Contact): Boolean {
        return containsNumber(c.number)
    }

    fun containsNumber(number: String): Boolean {
        for (ele in list) {
            if (PhoneNumberUtils.compare(ele.number, number)) {
                return true
            }
        }
        return false
    }

    fun add(c: Contact) {
        if (!contains(c)) {
            list.add(c)
            saveList()
        }
    }

    fun remove(phoneNumber: String) {
        val toRemove = mutableListOf<Contact>()
        for (ele in list) {
            if (PhoneNumberUtils.compare(ele.number, phoneNumber)) {
                toRemove.add(ele)
            }
        }
        list.removeAll(toRemove)
        saveList()
    }
}
