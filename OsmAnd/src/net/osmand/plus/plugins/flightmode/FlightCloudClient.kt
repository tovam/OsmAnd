package net.osmand.plus.plugins.flightmode

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject

internal data class FlightCloudConnection(val url: String, val token: String) {
    override fun toString(): String = "FlightCloudConnection(redacted)"

    val scope: String
        get() = cloudDigest((url + "\n" + token).toByteArray())

    companion object {
        fun validated(url: String, token: String): FlightCloudConnection {
            val uri = URI(url.trim().trimEnd('/'))
            require(
                uri.scheme == "https" &&
                    !uri.host.isNullOrBlank() &&
                    uri.rawUserInfo == null &&
                    uri.rawQuery == null &&
                    uri.rawFragment == null
            )
            require(token.trim().matches(Regex("[A-Za-z0-9_-]{24,256}")))
            return FlightCloudConnection(uri.toASCIIString().trimEnd('/'), token.trim())
        }
    }
}

internal data class FlightCloudEntry(
    val id: String,
    val name: String,
    val revision: String,
    val bytes: Long,
    val updatedAt: Long,
    val photoIds: Set<String>,
    val samples: Int,
) {
    companion object {
        fun parse(json: JSONObject): FlightCloudEntry {
            val id = json.getString("id")
            val revision = json.getString("revision")
            require(
                id.matches(Regex("[A-Za-z0-9_-]{1,80}")) && revision.matches(Regex("[a-f0-9]{64}"))
            )
            val photos = json.getJSONArray("photoIds")
            require(photos.length() <= 1000)
            return FlightCloudEntry(
                id,
                json.getString("name").take(300),
                revision,
                json.getLong("bytes").also { require(it in 1..FLIGHT_CLOUD_MAX_BYTES) },
                json.getLong("updatedAt"),
                (0 until photos.length()).map { photos.getString(it) }.toSet(),
                json.getInt("sampleCount").also { require(it >= 0) },
            )
        }
    }
}

internal class FlightCloudFailure(val code: String) : IOException(code)

internal data class FlightCloudLease(val token: String, val expiresElapsed: Long) {
    override fun toString(): String = "FlightCloudLease(redacted)"
}

/** Explicit, bounded transfers. Never follows redirects with an account token. */
internal class FlightCloudClient(private val connection: FlightCloudConnection) {
    private val active = AtomicReference<HttpURLConnection?>()

    fun cancel() {
        active.getAndSet(null)?.disconnect()
    }

    fun list(): List<FlightCloudEntry> =
        request("GET", "/v1/journeys") { conn ->
            val array = jsonResponse(conn).getJSONArray("journeys")
            List(array.length()) { FlightCloudEntry.parse(array.getJSONObject(it)) }
        }

    fun editSession(): FlightCloudLease =
        request("POST", "/v1/edit-session") { conn ->
            val started = android.os.SystemClock.elapsedRealtime()
            val response = jsonResponse(conn)
            val seconds = response.getInt("expiresInSeconds").coerceIn(1, 900)
            FlightCloudLease(response.getString("editToken"), started + seconds * 1000L)
        }

    fun upload(
        id: String,
        file: File,
        revision: String?,
        lease: FlightCloudLease,
        progress: (Long, Long) -> Unit,
    ): FlightCloudEntry {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        if (android.os.SystemClock.elapsedRealtime() >= lease.expiresElapsed)
            throw FlightCloudFailure("edit_session_expired")
        return request("PUT", "/v1/journeys/$id/archive") { conn ->
            conn.setRequestProperty("X-Edit-Token", lease.token)
            if (revision == null) conn.setRequestProperty("If-None-Match", "*")
            else conn.setRequestProperty("If-Match", "\"$revision\"")
            conn.setRequestProperty("Content-Type", "application/zip")
            conn.doOutput = true
            val length = file.length()
            require(length in 1..FLIGHT_CLOUD_MAX_BYTES)
            conn.setFixedLengthStreamingMode(length)
            conn.outputStream.use { output ->
                file.inputStream().buffered().use { input -> copy(input, output, length, progress) }
            }
            FlightCloudEntry.parse(jsonResponse(conn))
        }
    }

    fun download(entry: FlightCloudEntry, destination: File, progress: (Long, Long) -> Unit) =
        request("GET", "/v1/journeys/${entry.id}/archive") { conn ->
            conn.setRequestProperty("If-Match", "\"${entry.revision}\"")
            checkResponse(conn)
            val hash = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                destination.outputStream().buffered().use { output ->
                    java.security.DigestOutputStream(output, hash).use { digest ->
                        copy(input, digest, entry.bytes, progress)
                    }
                }
            }
            if (hash.digest().toHex() != entry.revision)
                throw FlightCloudFailure("checksum_mismatch")
        }

    private fun copy(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        size: Long,
        progress: (Long, Long) -> Unit,
    ) {
        val buffer = ByteArray(65536)
        var total = 0L
        var lastProgress = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > size) throw FlightCloudFailure("invalid_archive")
            output.write(buffer, 0, count)
            val now = System.nanoTime()
            if (now - lastProgress >= 150_000_000) {
                progress(total, size)
                lastProgress = now
            }
        }
        if (total != size) throw FlightCloudFailure("incomplete_transfer")
        progress(total, size)
    }

    private fun jsonResponse(conn: HttpURLConnection): JSONObject {
        checkResponse(conn)
        return conn.inputStream.use {
            JSONObject(it.readBytesBounded(8L * 1024 * 1024).toString(Charsets.UTF_8))
        }
    }

    private fun checkResponse(conn: HttpURLConnection) {
        val status = conn.responseCode
        if (status !in 200..299)
            throw FlightCloudFailure(
                when (status) {
                    401 -> "invalid_token"
                    403 -> "edit_session_expired"
                    409,
                    412 -> "revision_conflict"
                    413 -> "archive_too_large"
                    422 -> "invalid_archive"
                    else -> "http_$status"
                }
            )
    }

    private fun <T> request(method: String, path: String, action: (HttpURLConnection) -> T): T {
        val conn = URI(connection.url + path).toURL().openConnection() as HttpURLConnection
        active.set(conn)
        try {
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer ${connection.token}")
            conn.setRequestProperty("Accept", "application/json, application/zip")
            return action(conn)
        } finally {
            active.compareAndSet(conn, null)
            conn.disconnect()
        }
    }
}

internal fun cloudDigest(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it.toInt() and 255) }
