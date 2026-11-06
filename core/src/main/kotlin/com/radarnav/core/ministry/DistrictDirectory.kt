package com.radarnav.core.ministry

import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** İlçe listesinin kalıcı önbelleği (uygulamada dosya, testte bellek). */
interface DistrictCache {
    fun read(): String?
    fun write(content: String)
}

class InMemoryDistrictCache(var content: String? = null) : DistrictCache {
    override fun read() = content
    override fun write(content: String) {
        this.content = content
    }
}

/**
 * Bakanlığın 81 il / ~970 ilçelik listesi. Bakanlık API'si ilçe kimliğiyle çalıştığı için
 * herhangi bir koordinata en yakın ilçeyi bulmamız gerekiyor. Liste bir kez indirilip
 * [maxAgeMillis] boyunca önbellekte tutulur.
 */
class DistrictDirectory(
    private val api: MinistryApi,
    private val cache: DistrictCache,
    private val maxAgeMillis: Long = 30L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class CachedDistrict(
        val id: Int, val name: String, val cityId: Int, val cityName: String, val lat: Double, val lon: Double,
    )

    @Serializable
    private data class CacheFile(val savedAt: Long, val districts: List<CachedDistrict>)

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var districts: List<MinistryDistrict>? = null

    suspend fun all(): List<MinistryDistrict> = mutex.withLock {
        districts?.let { return it }
        val cached = loadCache()
        if (cached != null) {
            districts = cached
            return cached
        }
        val fresh = download()
        if (fresh.isNotEmpty()) {
            districts = fresh
            saveCache(fresh)
        }
        fresh
    }

    suspend fun nearest(p: LatLng): MinistryDistrict? = all().minByOrNull { Geo.distance(it.location, p) }

    private fun loadCache(): List<MinistryDistrict>? = runCatching {
        val text = cache.read() ?: return null
        val file = json.decodeFromString<CacheFile>(text)
        if (clock() - file.savedAt > maxAgeMillis || file.districts.isEmpty()) return null
        file.districts.map { MinistryDistrict(it.id, it.name, it.cityId, it.cityName, LatLng(it.lat, it.lon)) }
    }.getOrNull()

    private fun saveCache(list: List<MinistryDistrict>) {
        runCatching {
            val file = CacheFile(
                clock(),
                list.map { CachedDistrict(it.id, it.name, it.cityId, it.cityName, it.location.lat, it.location.lon) },
            )
            cache.write(json.encodeToString(file))
        }
    }

    private suspend fun download(): List<MinistryDistrict> = coroutineScope {
        val cities = api.cities()
        val limit = Semaphore(6)
        cities.map { city ->
            async { limit.withPermit { runCatching { api.districts(city) }.getOrDefault(emptyList()) } }
        }.awaitAll().flatten()
    }
}
