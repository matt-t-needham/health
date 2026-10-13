package computer.bix.health

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Live heart rate for up to a minute. Nothing is stored or uploaded. */
class LiveHrActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var bpmView: TextView
    private lateinit var intervalView: TextView
    private lateinit var stateView: TextView
    private lateinit var stopButton: Button
    @Volatile private var handle = 0L
    @Volatile private var finished = false
    private var liveSince = 0L

    private val seconds = 60
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val tick = object : Runnable {
        override fun run() {
            if (liveSince > 0 && !finished) {
                val left = (seconds - (System.currentTimeMillis() - liveSince) / 1000).coerceAtLeast(0)
                stateView.text = "Live, stops in $left s"
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val white = Color.WHITE
        val dim = Color.argb(138, 255, 255, 255)

        val appBar = TextView(this).apply {
            text = "Live heart rate"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            setBackgroundColor(getColor(R.color.charcoal_blue))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        bpmView = TextView(this).apply {
            text = "--"
            textSize = 96f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
        }
        val unit = TextView(this).apply { text = "bpm"; textSize = 16f; setTextColor(dim); gravity = Gravity.CENTER }
        intervalView = TextView(this).apply { text = "Beat interval: --"; textSize = 17f; setTextColor(white); gravity = Gravity.CENTER; setPadding(0, dp(24), 0, 0) }
        stateView = TextView(this).apply { text = "Starting"; textSize = 14f; setTextColor(dim); gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        stopButton = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            textSize = 16f
            stateListAnimator = null
            elevation = 0f
            setTextColor(white)
            background = GradientDrawable().apply { cornerRadius = dp(26).toFloat(); setColor(getColor(R.color.verdigris)) }
            setOnClickListener { stopOrClose() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { setColor(getColor(R.color.charcoal_blue)); cornerRadius = dp(16).toFloat() }
            elevation = dp(4).toFloat()
            setPadding(dp(16), dp(32), dp(16), dp(32))
            addView(bpmView); addView(unit); addView(intervalView); addView(stateView)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(18), dp(12), dp(24))
            addView(card, LinearLayout.LayoutParams(-1, -2))
            addView(stopButton, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(24) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_dark))
            addView(appBar, LinearLayout.LayoutParams(-1, -2))
            addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            appBar.setPadding(dp(16), dp(16) + bars.top, dp(16), dp(16))
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        handler.post(tick)
        Thread { run() }.start()
    }

    private fun state(text: String) = runOnUiThread { stateView.text = text }

    private fun run() {
        val needed = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (needed.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            end("Bluetooth permission needed; grant it from the main screen"); return
        }
        val store = Store.get(this)
        val keyHex = Secrets.get(this, "ring_key")
        val address = store.state("ring_address")
        if (keyHex == null || address == null) { end("Ring not paired"); return }

        synchronized(RingLock.lock) {
            if (finished) return
            state("Looking for ring")
            val hit = RingFinder.find(this, 30_000, lowLatency = true) { it.device.address == address }
            if (hit == null) { end("Ring not seen in 30 s"); return }
            if (finished) return

            state("Connecting")
            val h = NativeSync.nativeOpen()
            handle = h
            val link = RingLink(this, {}) { NativeSync.nativePush(h, it) }
            try {
                if (!link.open(hit.device)) { end("Could not open the ring link"); return }
                state("Authenticating")
                val bridge = object : NativeSync.Bridge {
                    override fun write(frame: ByteArray) = link.write(frame)
                    override fun onSerial(serial: String) = true
                    override fun onInfo(key: String, value: String) = true
                    override fun onBatch(tags: ByteArray, timestamps: IntArray, bodies: Array<ByteArray>, nextCursor: Int, bytesLeft: Int) = false
                    override fun onBeat(bpm: Int, ibiMs: Int): Boolean {
                        runOnUiThread {
                            if (liveSince == 0L) liveSince = System.currentTimeMillis()
                            bpmView.text = bpm.toString()
                            intervalView.text = "Beat interval: $ibiMs ms"
                        }
                        return true
                    }
                }
                runOnUiThread { liveSince = System.currentTimeMillis() }
                val err = NativeSync.nativeLiveHr(h, keyHex.hexToBytes(), seconds, bridge)
                end(err ?: "Finished", err != null)
            } finally {
                link.close()
                NativeSync.nativeClose(h)
                handle = 0
            }
        }
    }

    private fun end(message: String, isError: Boolean = false) {
        finished = true
        runOnUiThread {
            stateView.text = if (isError) "Problem: $message" else message
            stopButton.text = "Close"
            if (bpmView.text == "--" && !isError && message == "Finished") stateView.text = "Finished, no beats captured. Is the ring worn?"
        }
    }

    private fun stopOrClose() {
        if (finished) { finish(); return }
        state("Stopping")
        val h = handle
        if (h != 0L) NativeSync.nativeStop(h) else finished = true
    }

    override fun onStop() {
        // Leaving the screen ends the session so the ring is not left in live mode.
        val h = handle
        if (h != 0L) NativeSync.nativeStop(h)
        finished = true
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
