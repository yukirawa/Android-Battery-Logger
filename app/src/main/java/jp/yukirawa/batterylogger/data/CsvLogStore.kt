package jp.yukirawa.batterylogger.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

object CsvLogStore {
    private val fileLock = Any()

    fun appendBatch(context: Context, dateName: String, records: List<LogRecord>) {
        if (records.isEmpty()) return
        synchronized(fileLock) {
            val directory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }
            val file = appendFileForSchema(directory, dateName)
            RandomAccessFile(file, "rw").use { output ->
                val startingLength = output.length()
                output.seek(startingLength)
                try {
                    val text = buildString {
                        if (startingLength == 0L) append(LogRecord.CSV_HEADER).append('\n')
                        records.forEach { append(it.toCsvRow()).append('\n') }
                    }
                    output.write(text.toByteArray(StandardCharsets.UTF_8))
                    output.fd.sync()
                } catch (error: Exception) {
                    output.setLength(startingLength)
                    output.fd.sync()
                    throw error
                }
            }
        }
    }

    fun listFiles(context: Context): List<File> = synchronized(fileLock) {
        File(context.filesDir, LOG_DIRECTORY)
            .listFiles { file -> file.isFile && file.name.endsWith(".csv") }
            ?.sortedByDescending { it.name }
            .orEmpty()
    }

    fun latestFile(context: Context): File? = listFiles(context).firstOrNull()

    fun fileByName(context: Context, name: String): File? {
        if (!name.matches(LOG_FILE_NAME)) return null
        return listFiles(context).firstOrNull { it.name == name }
    }

    fun recoverIncompleteTails(context: Context) {
        synchronized(fileLock) {
            val files = File(context.filesDir, LOG_DIRECTORY)
                .listFiles { file -> file.isFile && file.name.endsWith(".csv") }
                .orEmpty()
            files.forEach { file ->
                RandomAccessFile(file, "rw").use { input ->
                    val length = input.length()
                    if (length == 0L) return@use
                    input.seek(length - 1)
                    if (input.readByte() == NEWLINE_BYTE) return@use

                    var inQuotes = false
                    var lastCompleteRowEnd = 0L
                    var position = 0L
                    while (position < length) {
                        input.seek(position)
                        val current = input.readByte()
                        if (current == QUOTE_BYTE) {
                            if (inQuotes && position + 1 < length) {
                                input.seek(position + 1)
                                if (input.readByte() == QUOTE_BYTE) {
                                    position += 2
                                    continue
                                }
                            }
                            inQuotes = !inQuotes
                        } else if (current == NEWLINE_BYTE && !inQuotes) {
                            lastCompleteRowEnd = position + 1
                        }
                        position++
                    }
                    input.setLength(lastCompleteRowEnd)
                    input.fd.sync()
                }
            }
        }
    }

    fun exportTo(context: Context, source: File, destination: Uri) {
        synchronized(fileLock) {
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("書き出し先を開けませんでした")
        }
    }

    private fun appendFileForSchema(directory: File, dateName: String): File {
        val primary = File(directory, "power_log_$dateName.csv")
        if (matchesCurrentSchema(primary)) return primary

        val versionBase = "power_log_${dateName}_schema-${LogRecord.SCHEMA_VERSION}"
        var candidate = File(directory, "$versionBase.csv")
        var collision = 2
        while (!matchesCurrentSchema(candidate)) {
            if (!candidate.exists() || candidate.length() == 0L) return candidate
            candidate = File(directory, "${versionBase}_$collision.csv")
            collision++
        }
        return candidate
    }

    private fun matchesCurrentSchema(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return true
        return runCatching {
            file.inputStream().bufferedReader(StandardCharsets.UTF_8).use { it.readLine() == LogRecord.CSV_HEADER }
        }.getOrDefault(false)
    }

    private const val LOG_DIRECTORY = "logs"
    private val LOG_FILE_NAME = Regex("power_log_\\d{4}-\\d{2}-\\d{2}(?:_schema-[0-9.]+(?:_\\d+)?)?\\.csv")
    private const val NEWLINE_BYTE: Byte = 0x0a
    private const val QUOTE_BYTE: Byte = 0x22
}
