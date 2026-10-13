package computer.bix.health

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/**
 * Secrets (ring key, relay credentials) encrypted with an AES key that lives in
 * the Android Keystore and never leaves it.
 */
object Secrets {
    private const val ALIAS = "health-secrets"
    private const val PREFS = "secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun put(ctx: Context, name: String, value: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val blob = c.iv + c.doFinal(value.toByteArray())
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(ctx: Context, name: String): String? {
        val b64 = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(b64, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12))
            String(c.doFinal(blob, 12, blob.size - 12))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * One-time provisioning from files/provision.json (pushed over adb run-as),
     * which is deleted as soon as it is imported. Returns a status line, or null
     * if there was nothing to import.
     */
    fun importProvisioning(ctx: Context): String? {
        val f = File(ctx.filesDir, "provision.json")
        if (!f.exists()) return null
        return try {
            val j = JSONObject(f.readText())
            for (k in listOf("ring_key", "ring_serial", "relay_url", "device_secret", "cf_client_id", "cf_client_secret")) {
                if (j.has(k)) put(ctx, k, j.getString(k))
            }
            "Provisioning imported"
        } catch (e: Exception) {
            "Provisioning file invalid: ${e.message}"
        } finally {
            f.delete()
        }
    }
}
