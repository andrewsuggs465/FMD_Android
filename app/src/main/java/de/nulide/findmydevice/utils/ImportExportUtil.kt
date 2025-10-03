package de.nulide.findmydevice.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.stream.JsonWriter
import de.nulide.findmydevice.R
import de.nulide.findmydevice.data.ALLOWLIST_FILENAME
import de.nulide.findmydevice.data.AllowlistRepository
import de.nulide.findmydevice.data.SETTINGS_FILENAME
import de.nulide.findmydevice.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream


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

    suspend fun exportData(uri: Uri) {
        writeToUri(context, uri) { outputStream ->
            val zipOutputStream = ZipOutputStream(outputStream)

            var entry = ZipEntry(SETTINGS_FILENAME)
            zipOutputStream.putNextEntry(entry)
            var writer = zipOutputStream.writer()
            SettingsRepository.getInstance(context).writeAsJson(writer)
            writer.flush()
            zipOutputStream.closeEntry()

            entry = ZipEntry(ALLOWLIST_FILENAME)
            zipOutputStream.putNextEntry(entry)
            writer = zipOutputStream.writer()
            AllowlistRepository.getInstance(context).writeAsJson(writer)
            writer.flush()
            zipOutputStream.closeEntry()

            zipOutputStream.close()
        }
    }

    suspend fun importData(uri: Uri) {
        readFromUri(context, uri) { inputStream ->
            val path = uri.path ?: throw Exception("Missing URI path")

            // Newer FMD versions export ZIP files
            if (path.endsWith(".zip")) {
                val zipInputStream = ZipInputStream(inputStream)
                // Skip unknown files and silently ignore missing files
                while (true) {
                    val entry = zipInputStream.nextEntry ?: break

                    if (entry.name == SETTINGS_FILENAME) {
                        SettingsRepository.getInstance(context).importFromStream(zipInputStream)
                    } else if (entry.name == ALLOWLIST_FILENAME) {
                        AllowlistRepository.getInstance(context).importFromStream(zipInputStream)
                    }
                }
            }
            // Support old exports
            else if (path.endsWith(".json")) {
                SettingsRepository.getInstance(context).importFromStream(inputStream)
            } else {
                throw Exception("Unsupported file format")
            }
        }
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
    writer.close()
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

suspend fun readFromUri(
    context: Context,
    uri: Uri,
    read: (InputStream) -> Unit,
) = withContext(Dispatchers.IO) {

    var inputStream: InputStream? = null
    try {
        inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext
        read(inputStream)
        withContext(Dispatchers.Main) {
            val text = context.getString(R.string.Settings_Import_Success)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        context.log().e(TAG, "Import failed:\n${e.stackTraceToString()}")
        withContext(Dispatchers.Main) {
            val text = context.getString(R.string.Settings_Import_Failed)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    } finally {
        inputStream?.close()
    }
}
