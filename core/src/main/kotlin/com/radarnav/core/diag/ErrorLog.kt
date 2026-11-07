package com.radarnav.core.diag

import com.radarnav.core.osm.TextStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulamanın son hataları. Yalnızca telefonda saklanır; kullanıcı isterse Ayarlar'dan paylaşır.
 * Android Auto hataları kayıt bırakmadan "beklenmedik hata" ekranına dönüştüğü için tek ipucu budur.
 */
class ErrorLog(
    private val store: TextStore,
    private val appVersion: String,
    private val maxEntries: Int = 10,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()

    fun record(where: String, t: Throwable) = synchronized(lock) {
        val entries = (listOf(format(where, t, clock(), appVersion)) + entries()).take(maxEntries)
        runCatching { store.write(KEY, entries.joinToString(SEPARATOR)) }
    }

    fun entries(): List<String> = synchronized(lock) {
        store.read(KEY)?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    }

    fun text(): String = entries().joinToString("\n\n")

    fun clear() = synchronized(lock) { runCatching { store.write(KEY, "") } }

    companion object {
        const val KEY = "errors"
        private const val SEPARATOR = "\n----\n"
        private const val APP_PACKAGE = "com.radarnav"

        fun format(where: String, t: Throwable, atMillis: Long, appVersion: String): String {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(atMillis))
            val trace = t.stackTraceToString().lines().take(40).joinToString("\n")
            return "$time · $appVersion · $where\n$trace"
        }

        /** Araba ekranına sığacak kısa özet: hata türü, mesajı ve uygulama kodundaki ilk satır. */
        fun short(t: Throwable): String {
            val root = generateSequence(t) { it.cause }.last()
            val frame = (root.stackTrace + t.stackTrace).firstOrNull { it.className.startsWith(APP_PACKAGE) }
            val where = frame?.let { " (${it.fileName}:${it.lineNumber})" }.orEmpty()
            val msg = root.message?.take(160)?.let { ": $it" }.orEmpty()
            return "${root.javaClass.simpleName}$msg$where"
        }
    }
}
