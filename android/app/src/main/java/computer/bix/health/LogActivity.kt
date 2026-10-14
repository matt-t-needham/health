package computer.bix.health

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Full-screen activity log with a copy-everything button. */
class LogActivity : Activity() {
    private lateinit var text: TextView
    private lateinit var scroll: ScrollView
    private val listener: () -> Unit = { show() }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, filled: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = label
        isAllCaps = false
        textSize = 16f
        stateListAnimator = null
        elevation = 0f
        val accent = getColor(R.color.verdigris)
        setTextColor(if (filled) Color.WHITE else accent)
        background = GradientDrawable().apply {
            cornerRadius = dp(26).toFloat()
            if (filled) setColor(accent) else { setColor(0); setStroke(dp(1), accent) }
        }
        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityLog.init(this)
        val appBar = TextView(this).apply {
            this.text = "Activity"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(getColor(R.color.charcoal_blue))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(Color.argb(200, 255, 255, 255))
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        scroll = ScrollView(this).apply { addView(text) }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            addView(button("Copy all", true) { copyAll() })
            addView(button("Close", false) { finish() })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg_dark))
            addView(appBar, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(buttons, LinearLayout.LayoutParams(-1, -2))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            appBar.setPadding(dp(16), dp(16) + bars.top, dp(16), dp(16))
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        setContentView(root)
        show()
    }

    override fun onStart() { super.onStart(); ActivityLog.listen(listener) }
    override fun onStop() { ActivityLog.unlisten(listener); super.onStop() }

    private fun show() {
        text.text = ActivityLog.text()
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun copyAll() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Activity log", ActivityLog.text()))
        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
    }
}
