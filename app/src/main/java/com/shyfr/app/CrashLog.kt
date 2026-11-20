package com.shyfr.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostyka bez komputera: zapisuje wyjątki Java, "kroki" uruchamiania oraz powody zakończenia procesu
 * (także awarie natywne, których Java nie widzi) i pokazuje je przy następnym starcie.
 * Raport nie zawiera kluczy ani treści rozmów.
 */
object Trace {
    @Volatile private var dir: File? = null
    fun init(c: Context) { dir = c.filesDir }

    @Synchronized fun log(msg: String) {
        runCatching {
            val f = File(dir, "trace.txt")
            if (f.exists() && f.length() > 12000) f.writeText(f.readLines().takeLast(60).joinToString("\n") + "\n")
            f.appendText(SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + msg + "\n")
        }
    }
    fun read(c: Context): String? = runCatching { File(c.filesDir, "trace.txt").takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(c: Context) { runCatching { File(c.filesDir, "trace.txt").delete() } }
}

object CrashLog {
    private fun f(c: Context) = File(c.filesDir, "crash.txt")
    private fun prefs(c: Context) = c.getSharedPreferences("shyfr_diag", Context.MODE_PRIVATE)

    fun install(app: Application) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { f(app).writeText("Wątek: ${t.name}\n" + describe(e)) }
            prev?.uncaughtException(t, e)
        }
    }

    /** Raport do pokazania albo null, jeśli od ostatniego zamknięcia raportu nic złego się nie stało. */
    /** Pełny raport na żądanie (Ustawienia), niezależnie od tego, czy była awaria. */
    fun fullReport(c: Context): String = buildString {
        append("=== Zakończenia procesu ===\n"); exitLines(c, 0).forEach { append(it).append('\n') }
        append("\n=== Kroki ===\n").append(Trace.read(c) ?: "(brak)")
        append("\n=== Logcat ===\n").append(logcatTail())
    }

    private fun dataDirs(c: Context) =
        c.dataDir.listFiles()?.filter { it.isDirectory && (it.name.startsWith("app_") || it.name == "files" || it.name == "cache") } ?: emptyList()

    private fun dataListing(c: Context): String = runCatching {
        val out = ArrayList<String>()
        fun walk(d: File, depth: Int) {
            if (depth > 3 || out.size > 80) return
            d.listFiles()?.sortedBy { it.name }?.forEach { f ->
                out.add("  ".repeat(depth) + f.name + if (f.isDirectory) "/" else " (${f.length()} B)")
                if (f.isDirectory) walk(f, depth + 1)
            }
        }
        dataDirs(c).forEach { out.add(it.name + "/"); walk(it, 1) }
        out.joinToString("\n")
    }.getOrElse { "(brak: $it)" }

    fun read(c: Context): String? {
        val crash = runCatching { f(c).takeIf { it.exists() }?.readText() }.getOrNull()
        val exits = exitLines(c, prefs(c).getLong("seen", 0))
        if (crash == null && exits.isEmpty()) return null
        return buildString {
            if (exits.isNotEmpty()) { append("=== Zakończenia procesu ===\n"); exits.forEach { append(it).append('\n') } }
            if (crash != null) append("\n=== Wyjątek Java ===\n").append(crash)
            append("\n=== Kroki przed zamknięciem ===\n").append(Trace.read(c) ?: "(brak)")
            append("\n=== Logcat ===\n").append(logcatTail())
        }
    }

    fun clear(c: Context) {
        runCatching { f(c).delete() }
        Trace.clear(c)
        prefs(c).edit().putLong("seen", System.currentTimeMillis()).apply()
    }

    /** Pełna migawka (kroki + logcat) do pokazania z Ustawień. Wywoływać poza głównym wątkiem. */
    fun snapshot(c: Context): String = buildString {
        append("=== Kroki ===\n").append(Trace.read(c) ?: "(brak)")
        append("\n=== Logcat ===\n").append(logcatTail())
    }

    private fun logcatTail(): String = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "800", "*:I"))
        val text = p.inputStream.bufferedReader().use { it.readText() }
        p.destroy()
        val noise = Regex("lock verification|Common causes|non-optimized|proguard|InteractionJank|Choreographer|ViewRootImpl|InputMethod|ImeTracker|SurfaceView|BLASTBufferQueue")
        text.lines().filter { it.isNotBlank() && !noise.containsMatchIn(it) }
            .takeLast(60).joinToString("\n").takeLast(6000)
    }.getOrElse { "(brak: $it)" }.ifBlank { "(brak wpisów)" }

    private fun fmt(t: Long) = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(t))

    private fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF (proces sam wywołał exit)"; 2 -> "SIGNALED (zabity sygnałem)"; 3 -> "LOW_MEMORY"
        4 -> "CRASH (wyjątek Java)"; 5 -> "CRASH_NATIVE (awaria kodu natywnego)"; 6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"; 12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"; 14 -> "FREEZER"; else -> "reason=$r"
    }

    private fun exitLines(c: Context, since: Long): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val skip = setOf(10, 11, 15, 16) // zamknięcie przez użytkownika, aktualizacja pakietu
        return runCatching {
            c.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(c.packageName, 0, 8)
                .filter {
                    it.timestamp > since && it.reason !in skip &&
                        !(it.reason == 1 && it.status == 0) // zwykłe, zamierzone zakończenie
                }
                .map { "${fmt(it.timestamp)} ${reasonName(it.reason)} status=${it.status} proc=${it.processName} opis=${it.description}" }
        }.getOrDefault(emptyList())
    }

    private fun describe(e: Throwable): String {
        val sb = StringBuilder()
        var x: Throwable? = e
        var i = 0
        while (x != null && i < 6) {
            sb.append(if (i == 0) "" else "Caused by: ").append(x.javaClass.name).append(": ").append(x.message).append("\n")
            x = x.cause; i++
        }
        sb.append("\n").append(android.util.Log.getStackTraceString(e).take(2500))
        return sb.toString()
    }
}

class ShyfrApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Trace.init(this)
        Trace.log("start procesu pid=${android.os.Process.myPid()}")
        CrashLog.install(this)
        com.shyfr.app.msg.PushRegistrar.ensureFirebase(this)   // no-op bez konfiguracji FCM
    }
}
