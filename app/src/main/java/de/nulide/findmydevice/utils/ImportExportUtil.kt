package de.nulide.findmydevice.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import com.google.gson.stream.JsonWriter
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.ALLOWLIST_FILENAME
import de.nulide.findmydevice.data.AllowlistRepository
import de.nulide.findmydevice.data.SETTINGS_FILENAME
import de.nulide.findmydevice.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lingala.zip4j.io.inputstream.ZipInputStream
import net.lingala.zip4j.io.outputstream.ZipOutputStream
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


private const val TAG = "ImportExportUtil"


class SettingsImportExporter(
    private val context: Context,
) {
    companion object {
        fun filenameForExport(): String {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val date = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                "fmd-settings-$date.zip"
            } else {
                "fmd-settings.zip"
            }
        }
    }

    suspend fun exportData(uri: Uri, password: String) {
        writeToUri(context, uri) { outputStream ->

            // https://github.com/srikanth-lingala/zip4j#working-with-streams
            val zipParas = ZipParameters().apply {
                compressionMethod = CompressionMethod.DEFLATE
                compressionLevel = CompressionLevel.NORMAL

                if (password.isNotBlank()) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                    aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
                }
            }
            val zipOutputStream = ZipOutputStream(outputStream, password.toCharArray())

            val filesToAdd = listOf(
                File(context.filesDir, SETTINGS_FILENAME),
                File(context.filesDir, ALLOWLIST_FILENAME),
            )

            for (file in filesToAdd) {
                zipParas.fileNameInZip = file.name
                zipOutputStream.putNextEntry(zipParas)
                file.inputStream().copyTo(zipOutputStream)
                zipOutputStream.closeEntry()
            }

            zipOutputStream.close()
        }
    }

    @Throws(JsonIOException::class, JsonSyntaxException::class)
    suspend fun importData(
        uri: Uri,
        password: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        // We cannot use the file ending (.zip/.json) to detect the file,
        // since the URI path may not have those (e.g. when the file comes from Nextcloud as a content provider).
        // Thus we just need to try one format, and if it fails, try the other.

        var success = importV1SettingsJson(uri)
        if (success) {
            return@withContext true
        }

        success = importV2Zip(uri, password)
        if (success) {
            return@withContext true
        }

        return@withContext false
    }

    // Old "settings.json"
    private fun importV1SettingsJson(uri: Uri): Boolean {
        var inputStream: InputStream? = null
        try {
            context.log().i(TAG, "Trying to import as JSON")
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                context.log().i(TAG, "Failed to open InputStream from URI")
                return false
            }

            SettingsRepository.getInstance(context).importFromStream(inputStream)

            // Apparently the import was successful
            context.log().i(TAG, "JSON import successful")
            return true
        } catch (e: Exception) {
            // continue
        } finally {
            inputStream?.close()
        }
        return false
    }

    // We need to open a new stream (or would need to reset the old stream).
    // Otherwise the ZIP reader starts reading wherever the JSON reader stopped.

    // New format, "fmd-export.zip" (contains settings.json and other data)
    private fun importV2Zip(
        uri: Uri,
        password: String?,
    ): Boolean {
        var inputStream: InputStream? = null
        try {
            context.log().i(TAG, "Trying to import ZIP")
            inputStream = context.contentResolver.openInputStream(uri)
            if (inputStream == null) {
                context.log().i(TAG, "Failed to open InputStream from URI")
                return false
            }

            val zipInputStream = ZipInputStream(inputStream, password?.toCharArray())
            var settingsFound = false
            var allowlistFound = false

            while (true) {
                val entry = zipInputStream.nextEntry ?: break

                when (entry.fileName) {
                    SETTINGS_FILENAME -> {
                        context.log().i(TAG, "Trying to import $SETTINGS_FILENAME")
                        SettingsRepository.getInstance(context).importFromStream(zipInputStream)
                        settingsFound = true
                    }

                    ALLOWLIST_FILENAME -> {
                        context.log().i(TAG, "Trying to import $ALLOWLIST_FILENAME")
                        AllowlistRepository.getInstance(context)
                            .importFromStream(zipInputStream)
                        allowlistFound = true
                    }

                    else -> {
                        context.log().w(TAG, "Unknown entry '${entry.fileName}'")
                    }
                }
            }

            if (settingsFound && allowlistFound) {
                context.log().i(TAG, "ZIP import successful")
                return true
            }

            // Consider this an import failure
            context.log().w(TAG, "ZIP: settingsFound=$settingsFound allowlistFound=$allowlistFound")

        } catch (e: Exception) {
            context.log().e(TAG, "ZIP import failed:\n${e.stackTraceToString()}")
            // continue
        } finally {
            inputStream?.close()
        }

        return false
    }
}

fun writeAsJson(
    outputStreamWriter: OutputStreamWriter,
    // Receive the Gson as parameter, to ensure it is configured with the settings that src needs
    gson: Gson,
    src: Any,
) {
    val type = src.javaClass
    val writer = JsonWriter(outputStreamWriter)
    gson.toJson(src, type, writer)

    // Don't close the JsonWriter, as this also closes all of the underlying writers.
    // This would close the entire ZIP file during settings export.
    // Callers of this function should close the outputStreamWriter themselves.
}

// Coroutines: read/write must happen on the IO thread.
// Because when the URI is backed by a remote location (e.g. Nextcloud) we otherwise get a NetworkOnMainThreadException.
// Switch back to the main thread for UI-related tasks (showing a Toast).

suspend fun writeToUri(
    context: Context,
    uri: Uri,
    write: (OutputStream) -> Unit,
) = withContext(Dispatchers.IO) {
    var outputStream: OutputStream? = null
    try {
        outputStream = context.contentResolver.openOutputStream(uri) ?: return@withContext
        write(outputStream)
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.export_success, Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        context.log().e(TAG, "Export failed:\n${e.stackTraceToString()}")
        withContext(Dispatchers.Main) {
            Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    } finally {
        outputStream?.close()
    }
}
