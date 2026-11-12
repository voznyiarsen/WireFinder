package com.wirefinder.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object CsvFileStore {

    const val MAX_CSV_SIZE = 5L * 1024 * 1024

    private const val FILE_NAME = "selected_networks.csv"
    private const val TEMP_FILE_NAME = "$FILE_NAME.tmp"
    private const val BUFFER_SIZE = 64 * 1024

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun copyFromUri(context: Context, uri: Uri): File? {
        val target = file(context)
        val temp = File(context.filesDir, TEMP_FILE_NAME)
        temp.delete()

        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            var tooLarge = false
            input.use { source ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_CSV_SIZE) {
                            tooLarge = true
                            break
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (tooLarge) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (e: Exception) {
            temp.delete()
            null
        }
    }

    fun delete(context: Context) {
        file(context).delete()
        File(context.filesDir, TEMP_FILE_NAME).delete()
    }
}
