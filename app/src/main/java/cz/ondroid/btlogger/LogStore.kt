package cz.ondroid.btlogger

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Jednoduchý zápis událostí do CSV souboru v úložišti aplikace.
 * Formát řádku: cas;ms;typ;zprava
 */
object LogStore {

    private const val HEADER = "cas;ms;typ;zprava"
    private const val MAX_RECENT = 300

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val recent = ArrayDeque<String>()
    private val counts = mutableMapOf<String, Int>()
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null

    /** Volá se na hlavním vlákně po každém zápisu. */
    var listener: (() -> Unit)? = null

    @Synchronized
    private fun ensure(ctx: Context): File {
        file?.let { return it }
        val f = File(ctx.applicationContext.filesDir, "bt_log.csv")
        if (!f.exists()) f.writeText(HEADER + "\n")
        // načti poslední řádky a počty
        f.forEachLine { line ->
            if (line == HEADER) return@forEachLine
            val parts = line.split(';', limit = 4)
            if (parts.size >= 3) counts[parts[2]] = (counts[parts[2]] ?: 0) + 1
            recent.addLast(line)
            if (recent.size > MAX_RECENT) recent.removeFirst()
        }
        file = f
        return f
    }

    @Synchronized
    fun log(ctx: Context, type: String, msg: String) {
        val f = ensure(ctx)
        val now = System.currentTimeMillis()
        val clean = msg.replace(';', ',').replace('\n', ' ')
        val line = "${fmt.format(Date(now))};$now;$type;$clean"
        try {
            f.appendText(line + "\n")
        } catch (_: Exception) {
        }
        recent.addLast(line)
        if (recent.size > MAX_RECENT) recent.removeFirst()
        counts[type] = (counts[type] ?: 0) + 1
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun recentLines(ctx: Context): List<String> {
        ensure(ctx)
        return recent.toList()
    }

    @Synchronized
    fun count(ctx: Context, type: String): Int {
        ensure(ctx)
        return counts[type] ?: 0
    }

    @Synchronized
    fun clear(ctx: Context) {
        val f = ensure(ctx)
        f.writeText(HEADER + "\n")
        recent.clear()
        counts.clear()
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun file(ctx: Context): File = ensure(ctx)

    /**
     * Pro každou značku vypíše události 8 s před ní a 2 s po ní.
     * Značka se dělá až po výpadku, proto je okno posunuté do minulosti.
     */
    @Synchronized
    fun markerReport(ctx: Context): String {
        val f = ensure(ctx)
        data class Row(val ms: Long, val type: String, val text: String)

        val rows = mutableListOf<Row>()
        f.forEachLine { line ->
            if (line == HEADER) return@forEachLine
            val p = line.split(';', limit = 4)
            val ms = p.getOrNull(1)?.toLongOrNull() ?: return@forEachLine
            rows.add(Row(ms, p.getOrElse(2) { "" }, line))
        }
        val markers = rows.filter { it.type == "ZNACKA" }
        if (markers.isEmpty()) return "Zatím žádné značky."

        val sb = StringBuilder()
        sb.append("Počet značek: ${markers.size}\n")
        var withEvent = 0
        markers.forEachIndexed { i, m ->
            val around = rows.filter {
                it !== m && it.type != "ZNACKA" && it.type != "HLASITOST" &&
                    it.ms in (m.ms - 8000)..(m.ms + 2000)
            }
            if (around.isNotEmpty()) withEvent++
            sb.append("\n=== Značka ${i + 1}: ${m.text.substringBefore(';')} ===\n")
            if (around.isEmpty()) {
                sb.append("  (žádná událost 8 s před ani 2 s po)\n")
            } else {
                around.forEach { r ->
                    val d = (r.ms - m.ms) / 1000.0
                    sb.append(String.format(Locale.US, "  %+.1f s  %s\n", d, r.text.split(';', limit = 4).drop(2).joinToString("  ")))
                }
            }
        }
        sb.insert(0, "Značek s nějakou událostí v okolí: $withEvent z ${markers.size}\n")
        return sb.toString()
    }
}
