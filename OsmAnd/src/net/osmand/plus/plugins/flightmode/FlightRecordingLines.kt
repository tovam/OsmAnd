package net.osmand.plus.plugins.flightmode

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import org.json.JSONObject

/** Repair only a crash-truncated last append. Complete malformed records are never skipped. */
internal object FlightRecordingLines {
    fun recoverTail(file: File) {
        if (!file.isFile || file.length() == 0L) return
        RandomAccessFile(file, "rw").use { input ->
            val length = input.length()
            input.seek(length - 1)
            if (input.read() == '\n'.code) return
            var start = length - 1
            while (start > 0 && length - start <= 1_048_576) {
                input.seek(start - 1)
                if (input.read() == '\n'.code) break
                start--
            }
            if (length - start > 1_048_576)
                throw IOException("Flight recording tail exceeds the recovery bound")
            val tail = ByteArray((length - start).toInt())
            input.seek(start)
            input.readFully(tail)
            if (runCatching { JSONObject(tail.toString(Charsets.UTF_8)) }.isSuccess) {
                // All JSON bytes reached disk; only the record separator was interrupted.
                input.seek(length)
                input.write('\n'.code)
                input.fd.sync()
            } else {
                val backup = File(file.parentFile, file.name + ".interrupted-" + UUID.randomUUID())
                check(backup.createNewFile())
                FileOutputStream(backup).use { out ->
                    out.write(tail)
                    out.fd.sync()
                }
                input.setLength(start)
                input.fd.sync()
            }
        }
    }
}
