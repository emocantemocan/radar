package com.radarnav.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.radarnav.core.geo.TileId
import com.radarnav.core.net.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Android Auto haritası için OSM karo önbelleği: bellekte LRU, diskte OkHttp önbelleği.
 */
class TileCache(private val http: OkHttpClient, private val settings: Settings) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val limit = Semaphore(4)

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    private fun url(t: TileId): String {
        val sub = "abcd"[(t.x + t.y) % 4].toString()
        return settings.tileUrl
            .replace("{z}", t.z.toString())
            .replace("{x}", t.x.toString())
            .replace("{y}", t.y.toString())
            .replace("{s}", sub)
    }

    /** Karo bellekteyse döner, değilse indirmeyi başlatır ve null döner. */
    fun get(t: TileId): Bitmap? {
        val dark = settings.darkMap
        val url = url(t)
        val key = if (dark) "night|$url" else url
        memory.get(key)?.let { return it }
        val lastFail = failed[url]
        if (lastFail != null && System.currentTimeMillis() - lastFail < 30_000) return null
        if (inFlight.add(key)) {
            scope.launch {
                try {
                    limit.withPermit {
                        http.newCall(Request.Builder().url(url).build()).await().use { resp ->
                            val bytes = resp.body?.bytes()
                            val raw = if (resp.isSuccessful && bytes != null) {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            } else null
                            val bmp = if (raw != null && dark) MapStyle.toNight(raw).also { raw.recycle() } else raw
                            if (bmp != null) {
                                memory.put(key, bmp)
                                failed.remove(url)
                            } else {
                                failed[url] = System.currentTimeMillis()
                            }
                        }
                    }
                } catch (e: Exception) {
                    failed[url] = System.currentTimeMillis()
                } finally {
                    inFlight.remove(key)
                }
                withContext(Dispatchers.Main) { listeners.forEach { it() } }
            }
        }
        return null
    }

    /** Eksik karonun yerine bir üst seviyedeki karonun ilgili parçasını kullanmak için. */
    fun peek(t: TileId): Bitmap? = memory.get(if (settings.darkMap) "night|${url(t)}" else url(t))
}
