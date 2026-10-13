package computer.bix.health

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pushes stored batches to the HTPC relay, oldest first, deleting each only after
 * a 2xx. The wire format is provisional: the relay side is not built yet.
 */
object Uploader {
    /** Returns null when the queue is empty, otherwise a one-line reason it is not. */
    fun flush(ctx: Context): String? {
        val store = Store.get(ctx)
        val base = Secrets.get(ctx, "relay_url") ?: return "Relay not configured"
        val deviceSecret = Secrets.get(ctx, "device_secret") ?: return "Relay credentials missing"
        val cfId = Secrets.get(ctx, "cf_client_id")
        val cfSecret = Secrets.get(ctx, "cf_client_secret")
        while (true) {
            val batch = store.nextPending() ?: break
            val err = post(base, batch, deviceSecret, cfId, cfSecret)
            if (err != null) {
                store.setState("last_push_error", err)
                return err
            }
            store.deleteBatch(batch.id)
            store.setState("last_push_ms", System.currentTimeMillis().toString())
            store.setState("last_push_error", "")
        }
        return null
    }

    private fun post(base: String, b: Store.PendingBatch, secret: String, cfId: String?, cfSecret: String?): String? {
        val events = JSONArray()
        for (i in b.tags.indices) {
            events.put(JSONArray().put(b.tags[i].toInt() and 0xff).put(b.timestamps[i].toLong() and 0xffffffffL).put(b.bodies[i].toHex()))
        }
        val json = JSONObject()
            .put("batch_id", "${b.serial}:${b.cursorBefore}:${b.cursorAfter}")
            .put("serial", b.serial)
            .put("cursor_before", b.cursorBefore)
            .put("cursor_after", b.cursorAfter)
            .put("events", events)
        val raw = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(json.toString().toByteArray()) } }.toByteArray()
        return try {
            val c = URL(base.trimEnd('/') + "/v1/batch").openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = 60_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Content-Encoding", "gzip")
            c.setRequestProperty("Authorization", "Bearer $secret")
            if (cfId != null && cfSecret != null) {
                c.setRequestProperty("CF-Access-Client-Id", cfId)
                c.setRequestProperty("CF-Access-Client-Secret", cfSecret)
            }
            c.outputStream.use { it.write(raw) }
            val code = c.responseCode
            c.disconnect()
            when {
                code in 200..299 -> null
                code in 300..399 || code == 401 || code == 403 -> "Rejected by relay or Cloudflare (HTTP $code)"
                else -> "Relay error (HTTP $code)"
            }
        } catch (e: Exception) {
            "Cannot reach relay: ${e.javaClass.simpleName}"
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
