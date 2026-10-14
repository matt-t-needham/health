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
 * a 2xx. Contract: docs/relay-plan.md.
 */
object Uploader {
    private sealed interface Outcome {
        object Accepted : Outcome
        /** HTTP 422: the HTPC will never accept this batch. */
        class Rejected(val reason: String) : Outcome
        /** Anything else: stop and try again later. */
        class Stop(val reason: String) : Outcome
    }

    /** Returns null when the queue is empty, otherwise a one-line reason it is not. */
    fun flush(ctx: Context): String? {
        val store = Store.get(ctx)
        val base = Secrets.get(ctx, "relay_url")?.takeIf { it.isNotBlank() } ?: return "Relay not configured"
        val deviceSecret = Secrets.get(ctx, "device_secret")?.takeIf { it.isNotBlank() } ?: return "Relay credentials missing"
        val cfId = Secrets.get(ctx, "cf_client_id")?.takeIf { it.isNotBlank() }
        val cfSecret = Secrets.get(ctx, "cf_client_secret")?.takeIf { it.isNotBlank() }
        while (true) {
            val batch = store.nextPending() ?: break
            when (val out = post(base, body(store, batch), deviceSecret, cfId, cfSecret)) {
                Outcome.Accepted -> {
                    store.deleteBatch(batch.id)
                    store.setState("last_push_ms", System.currentTimeMillis().toString())
                    store.setState("last_push_error", "")
                }
                is Outcome.Rejected -> {
                    // Set aside so one bad batch can't block the rest. The data stays on the phone.
                    store.markRejected(batch.id, out.reason)
                    ActivityLog.add("Batch ${batch.cursorBefore}-${batch.cursorAfter} rejected by the HTPC: ${out.reason}")
                }
                is Outcome.Stop -> {
                    store.setState("last_push_error", out.reason)
                    return out.reason
                }
            }
        }
        return null
    }

    private fun body(store: Store, b: Store.PendingBatch): ByteArray {
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
        if (b.anchorRingTs != null && b.anchorUnix != null) {
            json.put("anchor", JSONObject().put("ring_timestamp", b.anchorRingTs).put("unix_time", b.anchorUnix))
        }
        // Latest known ring facts; the HTPC keeps the newest.
        val device = JSONObject()
        store.state("ring_firmware")?.let { device.put("firmware", it) }
        store.state("ring_battery")?.toIntOrNull()?.let { device.put("battery", it) }
        if (device.length() > 0) json.put("device", device)
        store.state("feature_modes")?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { json.put("feature_modes", it) }
        return ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(json.toString().toByteArray()) } }.toByteArray()
    }

    private fun post(base: String, raw: ByteArray, secret: String, cfId: String?, cfSecret: String?): Outcome {
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
            val detail = if (code >= 400) runCatching { c.errorStream?.bufferedReader()?.readText()?.take(200)?.trim() }.getOrNull() else null
            c.disconnect()
            val suffix = if (detail.isNullOrEmpty()) "" else ": $detail"
            when {
                code in 200..299 -> Outcome.Accepted
                code == 422 -> Outcome.Rejected("HTTP 422$suffix")
                code in 300..399 || code == 401 || code == 403 -> Outcome.Stop("Rejected by relay or Cloudflare (HTTP $code)")
                else -> Outcome.Stop("Relay error (HTTP $code)")
            }
        } catch (e: Exception) {
            Outcome.Stop("Cannot reach relay: ${e.javaClass.simpleName}")
        }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
