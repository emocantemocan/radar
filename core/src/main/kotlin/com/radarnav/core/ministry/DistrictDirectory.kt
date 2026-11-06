package com.radarnav.core.ministry

import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** İl/ilçe listelerinin kalıcı önbelleği (uygulamada dosya, testte bellek). */
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
 * Bakanlığın il ve ilçe listeleri. Bakanlık API'si ilçe kimliğiyle çalıştığı için bir koordinata
 * en yakın ilçeyi bulmamız gerekiyor. 81 ilin hepsini indirmek yerine yalnızca koordinata en
 * yakın birkaç ilin ([candidateProvinces]) ilçe listesi istenir ve [maxAgeMillis] boyunca saklanır.
 */
class DistrictDirectory(
    private val api: MinistryApi,
    private val cache: DistrictCache,
    private val locator: ProvinceLocator = Provinces,
    private val candidateProvinces: Int = 3,
    private val maxAgeMillis: Long = 30L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    private data class CachedCity(val id: Int, val name: String)

    @Serializable
    private data class CachedDistrict(val id: Int, val name: String, val lat: Double, val lon: Double)

    @Serializable
    private data class CacheFile(
        val savedAt: Long,
        val cities: List<CachedCity>,
        val districts: Map<Int, List<CachedDistrict>> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var cities: List<MinistryCity>? = null
    private val districts = HashMap<Int, List<MinistryDistrict>>()
    private var savedAt = 0L
    private var loaded = false

    private fun loadCache() {
        if (loaded) return
        loaded = true
        val file = runCatching { cache.read()?.let { json.decodeFromString<CacheFile>(it) } }.getOrNull() ?: return
        if (clock() - file.savedAt > maxAgeMillis || file.cities.isEmpty()) return
        savedAt = file.savedAt
        cities = file.cities.map { MinistryCity(it.id, it.name) }
        val byId = cities!!.associateBy { it.id }
        file.districts.forEach { (cityId, list) ->
            val city = byId[cityId] ?: return@forEach
            districts[cityId] = list.map { MinistryDistrict(it.id, it.name, city.id, city.name, LatLng(it.lat, it.lon)) }
        }
    }

    private fun saveCache() {
        val c = cities ?: return
        runCatching {
            if (savedAt == 0L) savedAt = clock()
            val file = CacheFile(
                savedAt,
                c.map { CachedCity(it.id, it.name) },
                districts.mapValues { (_, list) -> list.map { CachedDistrict(it.id, it.name, it.location.lat, it.location.lon) } },
            )
            cache.write(json.encodeToString(file))
        }
    }

    suspend fun cities(): List<MinistryCity> = mutex.withLock { citiesLocked() }

    private suspend fun citiesLocked(): List<MinistryCity> {
        loadCache()
        cities?.let { return it }
        val fresh = api.cities()
        if (fresh.isEmpty()) throw MinistryException("İl listesi boş geldi")
        cities = fresh
        savedAt = clock()
        saveCache()
        return fresh
    }

    private suspend fun districtsLocked(city: MinistryCity): List<MinistryDistrict> {
        districts[city.id]?.let { return it }
        val list = api.districts(city)
        if (list.isNotEmpty()) {
            districts[city.id] = list
            saveCache()
        }
        return list
    }

    /** Koordinata en yakın bakanlık ilçesi (yalnızca yakın illerin listeleri indirilir). */
    suspend fun nearest(p: LatLng): MinistryDistrict? = mutex.withLock {
        val all = citiesLocked()
        val wanted = locator.candidates(p, candidateProvinces)
        val matched = wanted.mapNotNull { name ->
            all.firstOrNull { Provinces.normalize(it.name) == Provinces.normalize(name) }
                ?: all.firstOrNull { Provinces.matches(it.name, name) }
        }.distinctBy { it.id }
        var lastError: Exception? = null
        val candidates = matched.flatMap { city ->
            try {
                districtsLocked(city)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                lastError = e
                emptyList()
            }
        }
        if (candidates.isEmpty()) lastError?.let { throw it }
        candidates.minByOrNull { Geo.distance(it.location, p) }
    }
}
