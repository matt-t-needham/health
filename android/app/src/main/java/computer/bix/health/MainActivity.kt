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
    private fun card(accent: Int, content: LinearLayout, fill: Boolean = false): LinearLayout {
        val bar = android.view.View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(accent)
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f, dp(16).toFloat(), dp(16).toFloat())
            }
        }
        content.setPadding(dp(14), dp(10), dp(14), dp(10))
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(charcoal)
                cornerRadius = dp(16).toFloat()
            }
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(6), 0, dp(6)) }
            addView(bar, LinearLayout.LayoutParams(dp(7), -1))
            addView(content, LinearLayout.LayoutParams(0, if (fill) -1 else -2, 1f))
        }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 11f
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
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(4), 0, dp(4)) }
        setOnClickListener { action() }
    }

    private val logListener: () -> Unit = { showLog() }
    private lateinit var logScroll: ScrollView

    private fun cell(key: String, name: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(label(name))
        addView(rows[key])
    }

    private fun pair(left: LinearLayout, right: LinearLayout?, top: Int = 10) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(if (top == 10) 6 else top), 0, 0)
        addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        addView(right ?: android.view.View(this@MainActivity), LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** Small scrolling view of recent activity, with an Expand button on its left. */
    private fun logViewport(): LinearLayout {
        log = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextColor(white(0.7f))
        }
        logScroll = ScrollView(this).apply { addView(log) }
        val expand = Button(this).apply {
            text = "Expand"
            isAllCaps = false
            textSize = 12f
            stateListAnimator = null
            elevation = 0f
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            setTextColor(verdigris)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(14).toFloat(); setColor(0); setStroke(dp(1), verdigris)
            }
            setOnClickListener { startActivity(android.content.Intent(this@MainActivity, LogActivity::class.java)) }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(expand, LinearLayout.LayoutParams(-2, dp(28)))
            addView(label("Activity").apply { setPadding(dp(10), 0, 0, 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6) })
        }
    }

    private fun showLog() {
        log.text = ActivityLog.text()
        logScroll.post { logScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.init(this)

        rows = linkedMapOf(
            "ring" to value(), "battery" to value(), "firmware" to value(), "features" to value(),
            "sync" to value(), "push" to value(),
        )
        rows.values.forEach { it.textSize = 14f }
        val statusBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pair(cell("ring", "Ring"), cell("battery", "Ring battery"), 0))
            addView(pair(cell("firmware", "Ring firmware"), cell("features", "Ring features")))
            addView(pair(cell("sync", "Last sync from ring"), cell("push", "Last push to HTPC")))
        }
        problemText = value()
        problemCard = card(peach, LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Problem"))
            addView(problemText)
        }).apply { visibility = android.view.View.GONE }

        fun halfWidth(v: Button) = v.also {
            it.layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), dp(4), dp(4), 0) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(10))
            addView(card(verdigris, statusBox))
            addView(problemCard)
            addView(card(verdigris, logViewport(), fill = true).also { c ->
                c.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { setMargins(0, dp(6), 0, dp(6)) }
            })
            pairButton = button("Pair ring", false) { requirePermissions { confirmPair() } }
            pairButton.visibility = android.view.View.GONE
            addView(pairButton)
            addView(button("Live heart rate", true) { requirePermissions { startActivity(android.content.Intent(this@MainActivity, LiveHrActivity::class.java)) } })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(-dp(4), 0, -dp(4), 0)
                addView(halfWidth(button("Sync now", false) { requirePermissions { Scheduler.syncNow(this@MainActivity); append("Sync requested") } }))
                addView(halfWidth(button("Full Stats", false) { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(STATS_URL))) }))
            })
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
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
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
        showLog()
    }

    override fun onResume() {
        super.onResume()
        Secrets.importProvisioning(this)?.let { append(it) }
        handler.post(refresh)
        ActivityLog.listen(logListener)
        showLog()
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        ActivityLog.unlisten(logListener)
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
        rows["features"]?.text = s.state("ring_features")?.let { if (it == "ok") "Set" else "Some failed" } ?: "Not set"
        rows["sync"]?.text = at("last_sync_ms") + events
        val n = s.pendingCount()
        rows["push"]?.text = at("last_push_ms") + if (n == 0) "" else "\n" + (if (n == 1) "1 batch waiting" else "$n batches waiting")
        val rejected = s.rejectedCount()
        val rejectedNote = if (rejected == 0) null else
            (if (rejected == 1) "1 batch" else "$rejected batches") + " rejected by the HTPC, kept on the phone. Sync now retries."
        val problem = listOf(s.state("last_error"), s.state("last_push_error"), rejectedNote).filter { !it.isNullOrEmpty() }
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

    private fun append(line: String) = ActivityLog.add(line)
}
