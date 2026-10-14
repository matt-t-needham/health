package computer.bix.health

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Timestamped activity lines shared by the screens and the background workers.
 * Kept in a small file so a sync that ran while the app was closed is still visible.
 */
object ActivityLog {
    private const val MAX = 500
    private val lines = ArrayList<String>()
    private var file: File? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "activity.log")
        file = f
        if (f.exists()) lines.addAll(f.readLines().takeLast(MAX))
    }

    @Synchronized
    fun add(message: String) {
        val line = "${clock.format(java.util.Date())}  $message"
        lines.add(line)
        val trimmed = lines.size > MAX
        if (trimmed) lines.removeAt(0)
        try {
            if (trimmed) file?.writeText(lines.joinToString("\n") + "\n") else file?.appendText(line + "\n")
        } catch (e: Exception) {
            // The in-memory copy is still correct; losing persistence is not worth failing a sync.
        }
        main.post { listeners.forEach { it() } }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
}
