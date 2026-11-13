package com.spook.musicplayer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile

data class PakEntry(
    val id: Int,
    val name: String,
    val offset: Long,
    val length: Long
) {
    val group: String
        get() {
            val index = name.indexOf('\\')
            return if (index < 0) "Other" else name.substring(0, index)
        }

    val title: String
        get() {
            val file = name.substringAfterLast('\\')
            return if (file.endsWith(".mp3", ignoreCase = true)) file.dropLast(4) else file
        }
}

class PakException(message: String) : Exception(message)

/**
 * Reads and appends to muzik.pak using the exact same layout as the Windows/iOS builds:
 *
 *   "MUSPAK1_" + 3 zero bytes
 *   repeat until EOF:
 *     int32 (little endian)  name length in bytes (UTF-8)
 *     byte[]                 name, e.g. "Artist\Artist - Title.mp3"
 *     int64 (little endian)  data length
 *     byte[]                 mp3 data
 */
class PakLibrary(private val file: File) {
    val entries = mutableListOf<PakEntry>()

    companion object {
        const val FILE_NAME = "muzik.pak"
        private const val MAGIC = "MUSPAK1_"
        private const val HEADER_SIZE = 11
        private val ZERO = byteArrayOf(0, 0, 0)
    }

    fun load() {
        entries.clear()
        if (!file.exists()) return

        RandomAccessFile(file, "r").use { raf ->
            val end = raf.length()
            if (end < HEADER_SIZE) throw PakException("muzik.pak bozuk veya okunamadı.")

            val magic = ByteArray(8)
            raf.seek(0)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != MAGIC) {
                throw PakException("muzik.pak tanınmadı (başlık MUSPAK1_ değil).")
            }

            raf.seek(HEADER_SIZE.toLong())
            var index = 0
            while (raf.filePointer < end) {
                val nameLength = readIntLE(raf)
                if (nameLength <= 0 || nameLength > 4096) throw PakException("muzik.pak bozuk.")

                val nameBytes = ByteArray(nameLength)
                raf.readFully(nameBytes)

                val length = readLongLE(raf)
                val dataOffset = raf.filePointer
                if (length < 0 || dataOffset + length > end) {
                    throw PakException("muzik.pak bozuk: geçersiz boyut.")
                }

                entries.add(PakEntry(index, String(nameBytes, Charsets.UTF_8), dataOffset, length))
                index++
                raf.seek(dataOffset + length)
            }
        }
    }

    fun copyTo(entry: PakEntry, output: OutputStream) {
        FileInputStream(file).use { input ->
            input.channel.position(entry.offset)
            val buffer = ByteArray(1 shl 20)
            var remaining = entry.length
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read < 0) throw PakException("Şarkı verisi eksik.")
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    fun add(context: Context, uri: Uri, fileName: String): PakEntry {
        ensureHeader()
        val name = uniqueName("Added\\" + fileName)

        val temp = File.createTempFile("import", ".mp3", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output, 1 shl 20) }
            } ?: throw PakException("Dosya açılamadı.")

            val size = temp.length()
            FileOutputStream(file, true).use { output ->
                val nameBytes = name.toByteArray(Charsets.UTF_8)
                output.write(int32LE(nameBytes.size))
                output.write(nameBytes)
                output.write(int64LE(size))
                FileInputStream(temp).use { input -> input.copyTo(output, 1 shl 20) }
            }

            val entry = PakEntry(entries.size, name, file.length() - size, size)
            entries.add(entry)
            return entry
        } finally {
            temp.delete()
        }
    }

    private fun ensureHeader() {
        if (file.exists() && file.length() >= HEADER_SIZE) return
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { output ->
            output.write(MAGIC.toByteArray(Charsets.US_ASCII))
            output.write(ZERO)
        }
    }

    private fun uniqueName(name: String): String {
        val existing = entries.map { it.name.lowercase() }.toSet()
        if (name.lowercase() !in existing) return name

        val directory = name.substringBeforeLast('\\', "")
        val filePart = name.substringAfterLast('\\')
        val dot = filePart.lastIndexOf('.')
        val base = if (dot >= 0) filePart.substring(0, dot) else filePart
        val extension = if (dot >= 0) filePart.substring(dot) else ""

        var counter = 2
        while (true) {
            val candidateFile = "$base ($counter)$extension"
            val candidate = if (directory.isEmpty()) candidateFile else "$directory\\$candidateFile"
            if (candidate.lowercase() !in existing) return candidate
            counter++
        }
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toInt() and 0xff) or
            ((b[1].toInt() and 0xff) shl 8) or
            ((b[2].toInt() and 0xff) shl 16) or
            ((b[3].toInt() and 0xff) shl 24)
    }

    private fun readLongLE(raf: RandomAccessFile): Long {
        val b = ByteArray(8)
        raf.readFully(b)
        var value = 0L
        for (i in 0..7) {
            value = value or ((b[i].toLong() and 0xffL) shl (8 * i))
        }
        return value
    }

    private fun int32LE(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte()
    )

    private fun int64LE(value: Long): ByteArray = ByteArray(8) { i ->
        ((value ushr (8 * i)) and 0xffL).toByte()
    }
}
