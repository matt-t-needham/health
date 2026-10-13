package computer.bix.health

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Status and three buttons. All state is shown as words, never colour alone. */
private const val STATS_URL = "https://health.bix.computer"

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("EEE d MMM HH:mm", Locale.US)
    private lateinit var log: TextView
    @Volatile private var busy = false

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 2000)
        }
    }

    private val charcoal by lazy { getColor(R.color.charcoal_blue) }
    private val verdigris by lazy { getColor(R.color.verdigris) }
    private val peach by lazy { getColor(R.color.burnt_peach) }
    private val bg by lazy { getColor(R.color.bg_dark) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun white(alpha: Float) = android.graphics.Color.argb((alpha * 255).toInt(), 255, 255, 255)

    private lateinit var rows: LinkedHashMap<String, TextView>
    private lateinit var problemCard: LinearLayout
    private lateinit var problemText: TextView
    private lateinit var pairButton: Button

    /** Card with a 7dp accent bar on the left, rounded on the left corners only. */
    private fun card(accent: Int, content: LinearLayout): LinearLayout {
        val bar = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent)
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f, dp(16).toFloat(), dp(16).toFloat())
            }
        }
        content.setPadding(dp(16), dp(14), dp(16), dp(14))
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(charcoal)
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
            addView(bar, LinearLayout.LayoutParams(dp(7), -1))
            addView(content, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(white(0.54f))
    }

    private fun value() = TextView(this).apply {
        textSize = 17f
        setTextColor(android.graphics.Color.WHITE)
    }

    private fun button(text: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        stateListAnimator = null
        elevation = 0f
        setTextColor(if (filled) android.graphics.Color.WHITE else verdigris)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            if (filled) setColor(verdigris) else { setColor(0); setStroke(dp(1), verdigris) }
        }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(6), 0, dp(6)) }
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(white(0.54f))
        }

        rows = linkedMapOf(
            "ring" to value(), "battery" to value(), "firmware" to value(), "sync" to value(), "push" to value(), "queue" to value(),
        )
        val statusBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((key, name) in listOf("ring" to "Ring", "battery" to "Ring battery", "firmware" to "Ring firmware", "sync" to "Last sync from ring", "push" to "Last push to HTPC", "queue" to "Waiting to push")) {
            statusBox.addView(label(name).apply { if (key != "ring") setPadding(0, dp(10), 0, 0) })
            statusBox.addView(rows[key])
        }
        problemText = value()
        problemCard = card(peach, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Problem"))
            addView(problemText)
        }).apply { visibility = android.view.View.GONE }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(24))
            addView(card(verdigris, statusBox))
            addView(problemCard)
            addView(button("Live heart rate", true) { requirePermissions { startActivity(android.content.Intent(this@MainActivity, LiveHrActivity::class.java)) } }
                .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(18) })
            addView(button("Sync now", false) { requirePermissions { Scheduler.syncNow(this@MainActivity); append("Sync requested") } })
            addView(button("Full Stats", false) { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(STATS_URL))) })
            pairButton = button("Pair ring", false) { requirePermissions { confirmPair() } }
            pairButton.visibility = android.view.View.GONE
            addView(pairButton)
            addView(label("Diagnostics").apply { setPadding(dp(4), dp(18), 0, dp(2)) })
            addView(button("Scan for ring (60 s)", false) { requirePermissions { scanOnly() } })
            addView(label("Activity").apply { setPadding(dp(4), dp(18), 0, dp(6)) })
            addView(log)
        }
        val appBar = TextView(this).apply {
            text = "Health"
            textSize = 20f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(charcoal)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            addView(appBar, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@MainActivity).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        // Edge to edge: keep the app bar and content clear of the system bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            appBar.setPadding(dp(16), dp(16) + bars.top, dp(16), dp(16))
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        Secrets.importProvisioning(this)?.let { append(it) }
        Scheduler.ensurePeriodic(this)
    }

    override fun onResume() {
        super.onResume()
        Secrets.importProvisioning(this)?.let { append(it) }
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render() {
        val s = Store.get(this)
        val ring = when (s.state("ring_state") ?: if (s.state("ring_address") == null) "not paired" else "disconnected") {
            "connected" -> "Connected"
            "connecting" -> "Connecting"
            "not seen" -> "Not seen"
            "not paired" -> "Not paired"
            else -> "Not connected"
        }
        fun at(key: String) = s.state(key)?.toLongOrNull()?.let { stamp.format(Date(it)) } ?: "Never"
        val events = s.state("last_sync_events")?.let { " ($it events)" } ?: ""
        rows["ring"]?.text = ring
        // Pairing is only offered when there is no usable bond.
        pairButton.visibility = if (ring == "Not paired") android.view.View.VISIBLE else android.view.View.GONE
        rows["battery"]?.text = s.state("ring_battery")?.let { "$it%" } ?: "Unknown"
        rows["firmware"]?.text = s.state("ring_firmware") ?: "Unknown"
        rows["sync"]?.text = at("last_sync_ms") + events
        rows["push"]?.text = at("last_push_ms")
        val n = s.pendingCount()
        rows["queue"]?.text = if (n == 1) "1 batch" else "$n batches"
        val problem = listOf(s.state("last_error"), s.state("last_push_error")).filter { !it.isNullOrEmpty() }
        problemCard.visibility = if (problem.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        problemText.text = problem.joinToString("\n")
    }

    private fun requirePermissions(then: () -> Unit) {
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            requestPermissions(needed, 1)
            append("Grant the permissions, then tap again")
            return
        }
        then()
    }

    private fun confirmPair() {
        AlertDialog.Builder(this, R.style.AppDialog)
            .setTitle("Pair ring")
            .setMessage("The ring must be factory-reset first. This bonds the phone, installs the stored key and restarts the sync position at zero.")
            .setPositiveButton("Pair") { _, _ -> runBusy("Pairing") { Pairer.pair(this, ::append) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun scanOnly() = runBusy("Scanning") {
        val seen = java.util.concurrent.atomic.AtomicInteger()
        RingFinder.find(this, 60_000, lowLatency = false) { r ->
            append("${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  ${r.device.address}  ${r.rssi} dBm")
            seen.incrementAndGet()
            false
        }
        append("Scan finished: ${seen.get()} adverts")
        null
    }

    private fun runBusy(what: String, body: () -> String?) {
        if (busy) { append("Already busy"); return }
        busy = true
        Thread {
            append("$what started")
            val err = try { body() } catch (e: Exception) { "$what failed: ${e.javaClass.simpleName}" }
            append(if (err == null) "$what finished" else "$what failed: $err")
            busy = false
        }.start()
    }

    private fun append(line: String) = runOnUiThread { log.append(line + "\n") }
}
