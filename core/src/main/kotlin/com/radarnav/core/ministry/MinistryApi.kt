package com.radarnav.core.ministry

import com.radarnav.core.geo.LatLng
import com.radarnav.core.model.Radar
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.net.anyCase
import com.radarnav.core.net.arr
import com.radarnav.core.net.bool
import com.radarnav.core.net.dbl
import com.radarnav.core.net.HttpException
import com.radarnav.core.net.fetchJson
import com.radarnav.core.net.fetchString
import com.radarnav.core.net.LenientJson
import com.radarnav.core.net.int
import com.radarnav.core.net.obj
import com.radarnav.core.net.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale

data class MinistryCity(val id: Int, val name: String)

data class CityCount(val city: String, val withRadar: Int, val withoutRadar: Int)

class MinistryException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class MinistryDistrict(
    val id: Int,
    val name: String,
    val cityId: Int,
    val cityName: String,
    val location: LatLng,
)

data class MinistryRouteData(
    val fromDistrict: String?,
    val toDistrict: String?,
    val radarCount: Int,
    val controlPointCount: Int,
    val corridorCount: Int,
    val radars: List<Radar>,
    val corridors: List<SpeedCorridor>,
    val coordinates: List<LatLng>,
    /** İl bazında günlük radarlı / radarsız trafik uygulaması sayıları (konum verilmese de gelir). */
    val cityCounts: List<CityCount> = emptyList(),
) {
    /** Bakanlık radar sayısı verdi ama konum listesi boş (servis yarım çalışıyor ya da konum paylaşmıyor). */
    val radarsWithheld get() = radarCount > 0 && radars.isEmpty()

    val activeRadarCount get() = radars.count { it.active }
    val activeCorridorCount get() = corridors.count { it.active }
}

/**
 * T.C. İçişleri Bakanlığı "İller Arası Radar ve Kontrol Noktası Uygulama Sayıları"
 * sayfasının kullandığı herkese açık uç noktalar.
 */
class MinistryApi(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://www.icisleri.gov.tr",
    /** Geçici hatalarda tekrar denemeler arası bekleme (testlerde kısaltılır). */
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000),
) {
    private val apiRoot get() = "$baseUrl/ISAYWebPart/PolGenControlPointV2"

    private fun Request.Builder.ministryHeaders() = apply {
        // Uygulama kendini açıkça tanıtır
        header("User-Agent", USER_AGENT)
        header("Referer", "$baseUrl/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari")
        header("Accept", "application/json, text/javascript, */*; q=0.01")
        header("Accept-Language", "tr-TR,tr;q=0.9")
    }

    /**
     * Yalnızca geçici hatalarda (ağ hatası, zaman aşımı, 429, 5xx) bekleyerek yeniden dener.
     * Sunucu isteği reddederse (403 vb.) tekrar denemez; ayrıntılı mesajla hata verir.
     */
    private suspend fun <T> retrying(what: String, block: suspend () -> T): T {
        var last: Exception? = null
        for (attempt in 0..retryDelaysMs.size) {
            try {
                return block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
                val transient = when (e) {
                    is HttpException -> e.code == 429 || e.code >= 500
                    is IOException -> true
                    else -> false
                }
                if (!transient || attempt == retryDelaysMs.size) break
                kotlinx.coroutines.delay(retryDelaysMs[attempt])
            }
        }
        throw MinistryException("$what: ${describe(last)}", last)
    }

    suspend fun cities(): List<MinistryCity> = retrying("İl listesi alınamadı") {
        val json = client.fetchJson(Request.Builder().url("$apiRoot/GetCities").ministryHeaders().get().build())
        listOf(json).unwrapList().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o.anyCase("Id").asInt() ?: return@mapNotNull null
            MinistryCity(id, o.anyCase("Name").asStr() ?: return@mapNotNull null)
        }
    }

    suspend fun districts(city: MinistryCity): List<MinistryDistrict> = retrying("${city.name} ilçeleri alınamadı") {
        val json = client.fetchJson(
            Request.Builder().url("$apiRoot/GetDistricts?cityId=${city.id}").ministryHeaders().get().build(),
        )
        listOf(json).unwrapList().mapNotNull { e ->
            val o = e.obj() ?: return@mapNotNull null
            val id = o.anyCase("Id").asInt() ?: return@mapNotNull null
            val lat = o.anyCase("Latitude").asDouble() ?: return@mapNotNull null
            val lon = o.anyCase("Longitude").asDouble() ?: return@mapNotNull null
            MinistryDistrict(id, o.anyCase("Name").asStr() ?: "", city.id, city.name, LatLng(lat, lon))
        }
    }

    suspend fun createRoute(from: MinistryDistrict, to: MinistryDistrict): MinistryRouteData =
        parseRoute(LenientJson.parseToJsonElement(createRouteRaw(from, to)))

    /**
     * Ham yanıt (kalıcı önbelleğe yazmak için). Bakanlık "success": false dönerse (ör. "Servis
     * çağrılırken hata oluştu"; sunucu zaman zaman böyle çöküyor) geçici hata sayılıp yeniden denenir.
     */
    suspend fun createRouteRaw(from: MinistryDistrict, to: MinistryDistrict): String =
        retrying("Radar sorgusu başarısız (${from.name} → ${to.name})") {
            createRouteOnce(from, to).also { parseRoute(LenientJson.parseToJsonElement(it)) }
        }

    private suspend fun createRouteOnce(from: MinistryDistrict, to: MinistryDistrict): String {
        val form = FormBody.Builder()
            .add("fromLatitude", from.location.lat.fmt())
            .add("fromLongitude", from.location.lon.fmt())
            .add("toLatitude", to.location.lat.fmt())
            .add("toLongitude", to.location.lon.fmt())
            .add("fromDistrictId", from.id.toString())
            .add("toDistrictId", to.id.toString())
            .build()
        return client.fetchString(
            Request.Builder().url("$apiRoot/CreateRoute").ministryHeaders().post(form).build(),
        )
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (compatible; RadarNav/0.2; Android; +https://github.com/emocantemocan/radar)"

        /** Hata için kullanıcıya gösterilebilecek açıklama (SSL, zaman aşımı, HTTP kodu ayrımı). */
        fun describe(e: Throwable?): String = when (e) {
            null -> "bilinmeyen hata"
            is HttpException -> when (e.code) {
                403 -> "sunucu isteği reddetti (HTTP 403)"
                404 -> "adres bulunamadı (HTTP 404; bakanlık servisi değişmiş olabilir)"
                429 -> "çok fazla istek (HTTP 429), biraz sonra tekrar denenecek"
                in 500..599 -> "bakanlık sunucusunda hata (HTTP ${e.code})"
                else -> "HTTP ${e.code}"
            }
            is javax.net.ssl.SSLException -> "güvenli bağlantı kurulamadı (${e.javaClass.simpleName}: ${e.message})"
            is java.net.SocketTimeoutException -> "zaman aşımı (sunucu cevap vermedi)"
            is java.net.UnknownHostException -> "internet yok ya da adres çözülemedi"
            is kotlinx.serialization.SerializationException -> "beklenmeyen yanıt biçimi (${e.message?.take(120)})"
            else -> "${e.javaClass.simpleName}: ${e.message.orEmpty()}".trim()
        }

        private fun Double.fmt() = String.format(Locale.US, "%.6f", this)

        /** Hem düz dizi hem de {"data": [...]} biçimini kabul eder. */
        private fun List<JsonElement>.unwrapList(): List<JsonElement> {
            val e = first()
            e.arr()?.let { return it }
            val o = e.obj() ?: return emptyList()
            return (o.anyCase("data") ?: o.anyCase("Data")).arr() ?: emptyList()
        }

        private fun JsonElement?.asStr(): String? =
            (this as? kotlinx.serialization.json.JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content

        private fun JsonElement?.asInt(): Int? = asStr()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        private fun JsonElement?.asDouble(): Double? = asStr()?.replace(',', '.')?.toDoubleOrNull()

        /** Aktif kabul edilen kayıtlar: activity == 3 (ya da alan hiç yoksa). */
        private fun JsonObject.isActive(): Boolean {
            val activity = int("activity") ?: return true
            return activity == 3
        }

        /**
         * [activeOnly] false ise (varsayılan) pasif kayıtlar da döner, [Radar.active] ile işaretli.
         * "activity" alanının tam anlamı belgelenmemiş (anlık denetim olabilir), bu yüzden kamerayı atmıyoruz.
         */
        fun parseRoute(json: JsonElement, activeOnly: Boolean = false): MinistryRouteData {
            val root = json.obj() ?: throw IOException("Beklenmeyen bakanlık yanıtı")
            if (root.bool("success") == false) {
                throw IOException("Bakanlık servisi hata döndü: ${root.str("message").orEmpty()}")
            }
            val data = root.anyCase("data").obj() ?: root
            val radars = data.anyCase("Radars").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val active = o.isActive()
                if (activeOnly && !active) return@mapNotNull null
                val lon = o.dbl("x") ?: return@mapNotNull null
                val lat = o.dbl("y") ?: return@mapNotNull null
                Radar(
                    id = "r:" + (o.str("id") ?: "${lat}_$lon"),
                    name = o.str("name")?.trim().orEmpty(),
                    province = o.str("provinceName")?.trim().orEmpty(),
                    district = o.str("districtName")?.trim().orEmpty(),
                    speedLimit = o.int("speedLimit")?.takeIf { it > 0 },
                    location = LatLng(lat, lon),
                    active = active,
                )
            }
            val corridors = data.anyCase("SpeedTunnels").arr().orEmpty().mapNotNull { e ->
                val o = e.obj() ?: return@mapNotNull null
                val active = o.isActive()
                if (activeOnly && !active) return@mapNotNull null
                val path = o.anyCase("coordinates").arr().orEmpty().mapNotNull { c ->
                    val co = c.obj() ?: return@mapNotNull null
                    val x = co.dbl("x") ?: return@mapNotNull null
                    val y = co.dbl("y") ?: return@mapNotNull null
                    LatLng(y, x)
                }
                val start = o.dbl("startLatY")?.let { lat -> o.dbl("startLonX")?.let { LatLng(lat, it) } }
                    ?: path.firstOrNull() ?: return@mapNotNull null
                val end = o.dbl("endLatY")?.let { lat -> o.dbl("endLonX")?.let { LatLng(lat, it) } }
                    ?: path.lastOrNull() ?: return@mapNotNull null
                SpeedCorridor(
                    id = "c:" + (o.str("id") ?: "${start}_$end"),
                    name = o.str("name")?.trim().orEmpty(),
                    province = o.str("provinceName")?.trim().orEmpty(),
                    district = o.str("districtName")?.trim().orEmpty(),
                    speedLimit = o.int("speedLimit")?.takeIf { it > 0 },
                    lengthKm = o.dbl("length"),
                    start = start,
                    end = end,
                    path = path.ifEmpty { listOf(start, end) },
                    active = active,
                )
            }
            val coords = data.anyCase("Coordinates").arr().orEmpty().mapNotNull { c ->
                val co = c.obj() ?: return@mapNotNull null
                val x = co.dbl("x") ?: return@mapNotNull null
                val y = co.dbl("y") ?: return@mapNotNull null
                LatLng(y, x)
            }
            val cityCounts = data.anyCase("Cities").arr().orEmpty().mapNotNull { c ->
                val co = c.obj() ?: return@mapNotNull null
                CityCount(co.str("City").orEmpty(), co.int("Radarli") ?: 0, co.int("Radarsiz") ?: 0)
            }
            return MinistryRouteData(
                cityCounts = cityCounts,
                fromDistrict = data.str("FromDistrict"),
                toDistrict = data.str("ToDistrict"),
                radarCount = data.int("RadarCount") ?: radars.size,
                controlPointCount = data.int("ControlPointCount") ?: 0,
                corridorCount = data.int("CorridorCount") ?: corridors.size,
                radars = radars,
                corridors = corridors,
                coordinates = coords,
            )
        }
    }
}
