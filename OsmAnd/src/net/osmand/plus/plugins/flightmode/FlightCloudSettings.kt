package net.osmand.plus.plugins.flightmode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Account token encrypted with Android Keystore; no cloud credentials in device backups. */
internal class FlightCloudSettings(context: Context) {
    private val configuration =
        AtomicFile(File(context.noBackupFilesDir, "flight-cloud-connection.json"))
    private val bindings = AtomicFile(File(context.noBackupFilesDir, "flight-cloud-bindings.json"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun load(): FlightCloudConnection? {
        if (!configuration.baseFile.exists()) return null
        return runCatching {
                val json =
                    configuration.openRead().use {
                        JSONObject(it.readBytesBounded(16384).toString(Charsets.UTF_8))
                    }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)),
                )
                val clear =
                    JSONObject(
                        cipher
                            .doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP))
                            .toString(Charsets.UTF_8)
                    )
                FlightCloudConnection.validated(clear.getString("url"), clear.getString("token"))
            }
            .getOrNull()
    }

    fun save(connection: FlightCloudConnection) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val clear =
            JSONObject()
                .put("url", connection.url)
                .put("token", connection.token)
                .toString()
                .toByteArray()
        write(
            configuration,
            JSONObject()
                .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(cipher.doFinal(clear), Base64.NO_WRAP)),
        )
    }

    fun disconnect() {
        configuration.delete()
    }

    fun bindings(scope: String): List<FlightCloudBinding> {
        val json = readBindings().optJSONObject(scope) ?: return emptyList()
        return json
            .keys()
            .asSequence()
            .mapNotNull { id ->
                runCatching {
                        val row = json.getJSONObject(id)
                        FlightCloudBinding(
                            id,
                            row.getString("remoteId"),
                            row.getString("revision"),
                            row.getLong("localUpdatedAt"),
                            row.optBoolean("allLocalPhotosIncluded", false),
                        )
                    }
                    .getOrNull()
            }
            .toList()
    }

    fun bind(scope: String, binding: FlightCloudBinding) {
        val root = readBindings()
        val account = root.optJSONObject(scope) ?: JSONObject()
        // Preserve provenance and the base revision of every older local copy.
        account.put(
            binding.localId,
            JSONObject()
                .put("remoteId", binding.remoteId)
                .put("revision", binding.revision)
                .put("localUpdatedAt", binding.localUpdatedAt)
                .put("allLocalPhotosIncluded", binding.allLocalPhotosIncluded),
        )
        root.put(scope, account)
        write(bindings, root)
    }

    private fun readBindings() =
        if (!bindings.baseFile.exists()) JSONObject()
        else
            bindings.openRead().use {
                JSONObject(it.readBytesBounded(8L * 1024 * 1024).toString(Charsets.UTF_8))
            }

    private fun write(file: AtomicFile, json: JSONObject) {
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    companion object {
        private const val ALIAS = "osmand-smart-flight-cloud"
    }
}
