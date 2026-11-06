package com.radarnav.core.ministry

import com.radarnav.core.geo.Geo
import com.radarnav.core.geo.LatLng
import java.text.Normalizer
import java.util.Locale

/** Bir koordinata yakın illerin adlarını verir (bakanlıktan yalnızca gerekli ilçeleri çekmek için). */
fun interface ProvinceLocator {
    fun candidates(p: LatLng, count: Int): List<String>
}

/**
 * 81 ilin merkez koordinatları. Bakanlık API'si ilçe kimliğiyle çalışıyor; 81 ilin tamamını
 * indirmek (82 istek) sunucunun güvenlik duvarına takılabildiği için yalnızca rotaya en yakın
 * birkaç ilin ilçe listesini isteriz.
 */
object Provinces : ProvinceLocator {

    val capitals: Map<String, LatLng> = linkedMapOf(
        "Adana" to LatLng(37.0000, 35.3213), "Adıyaman" to LatLng(37.7648, 38.2786),
        "Afyonkarahisar" to LatLng(38.7507, 30.5567), "Ağrı" to LatLng(39.7191, 43.0503),
        "Aksaray" to LatLng(38.3687, 34.0370), "Amasya" to LatLng(40.6499, 35.8353),
        "Ankara" to LatLng(39.9334, 32.8597), "Antalya" to LatLng(36.8969, 30.7133),
        "Ardahan" to LatLng(41.1105, 42.7022), "Artvin" to LatLng(41.1828, 41.8183),
        "Aydın" to LatLng(37.8560, 27.8416), "Balıkesir" to LatLng(39.6484, 27.8826),
        "Bartın" to LatLng(41.6344, 32.3375), "Batman" to LatLng(37.8812, 41.1351),
        "Bayburt" to LatLng(40.2552, 40.2249), "Bilecik" to LatLng(40.1451, 29.9799),
        "Bingöl" to LatLng(38.8847, 40.4939), "Bitlis" to LatLng(38.4006, 42.1095),
        "Bolu" to LatLng(40.7392, 31.6089), "Burdur" to LatLng(37.7203, 30.2908),
        "Bursa" to LatLng(40.1885, 29.0610), "Çanakkale" to LatLng(40.1553, 26.4142),
        "Çankırı" to LatLng(40.6013, 33.6134), "Çorum" to LatLng(40.5506, 34.9556),
        "Denizli" to LatLng(37.7765, 29.0864), "Diyarbakır" to LatLng(37.9144, 40.2306),
        "Düzce" to LatLng(40.8438, 31.1565), "Edirne" to LatLng(41.6818, 26.5623),
        "Elazığ" to LatLng(38.6810, 39.2264), "Erzincan" to LatLng(39.7500, 39.5000),
        "Erzurum" to LatLng(39.9000, 41.2700), "Eskişehir" to LatLng(39.7767, 30.5206),
        "Gaziantep" to LatLng(37.0662, 37.3833), "Giresun" to LatLng(40.9128, 38.3895),
        "Gümüşhane" to LatLng(40.4386, 39.5086), "Hakkari" to LatLng(37.5833, 43.7333),
        "Hatay" to LatLng(36.2021, 36.1600), "Iğdır" to LatLng(39.9237, 44.0450),
        "Isparta" to LatLng(37.7648, 30.5566), "İstanbul" to LatLng(41.0082, 28.9784),
        "İzmir" to LatLng(38.4237, 27.1428), "Kahramanmaraş" to LatLng(37.5858, 36.9371),
        "Karabük" to LatLng(41.2061, 32.6204), "Karaman" to LatLng(37.1759, 33.2287),
        "Kars" to LatLng(40.6013, 43.0975), "Kastamonu" to LatLng(41.3887, 33.7827),
        "Kayseri" to LatLng(38.7312, 35.4787), "Kırıkkale" to LatLng(39.8468, 33.5153),
        "Kırklareli" to LatLng(41.7333, 27.2167), "Kırşehir" to LatLng(39.1425, 34.1709),
        "Kilis" to LatLng(36.7184, 37.1212), "Kocaeli" to LatLng(40.8533, 29.8815),
        "Konya" to LatLng(37.8667, 32.4833), "Kütahya" to LatLng(39.4167, 29.9833),
        "Malatya" to LatLng(38.3552, 38.3095), "Manisa" to LatLng(38.6191, 27.4289),
        "Mardin" to LatLng(37.3212, 40.7245), "Mersin" to LatLng(36.8000, 34.6333),
        "Muğla" to LatLng(37.2153, 28.3636), "Muş" to LatLng(38.9462, 41.7539),
        "Nevşehir" to LatLng(38.6939, 34.6857), "Niğde" to LatLng(37.9667, 34.6833),
        "Ordu" to LatLng(40.9839, 37.8764), "Osmaniye" to LatLng(37.0742, 36.2478),
        "Rize" to LatLng(41.0201, 40.5234), "Sakarya" to LatLng(40.6940, 30.4358),
        "Samsun" to LatLng(41.2928, 36.3313), "Siirt" to LatLng(37.9333, 41.9500),
        "Sinop" to LatLng(42.0231, 35.1531), "Sivas" to LatLng(39.7477, 37.0179),
        "Şanlıurfa" to LatLng(37.1591, 38.7969), "Şırnak" to LatLng(37.4187, 42.4918),
        "Tekirdağ" to LatLng(40.9833, 27.5167), "Tokat" to LatLng(40.3167, 36.5500),
        "Trabzon" to LatLng(41.0015, 39.7178), "Tunceli" to LatLng(39.1079, 39.5401),
        "Uşak" to LatLng(38.6823, 29.4082), "Van" to LatLng(38.4891, 43.4089),
        "Yalova" to LatLng(40.6500, 29.2667), "Yozgat" to LatLng(39.8181, 34.8147),
        "Zonguldak" to LatLng(41.4564, 31.7987),
    )

    override fun candidates(p: LatLng, count: Int): List<String> =
        capitals.entries.sortedBy { Geo.distance(it.value, p) }.take(count).map { it.key }

    /** "İstanbul", "ISTANBUL", "istanbul" → "istanbul"; "K.Maraş" → "kmaras". Eşleştirme için. */
    fun normalize(name: String): String {
        val lower = name.trim().lowercase(Locale.forLanguageTag("tr-TR"))
            .replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        return Normalizer.normalize(lower, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z]"), "")
    }

    /** Bakanlığın il adını bizim tablodaki adla eşleştirir (kısaltmalara toleranslı). */
    fun matches(ministryName: String, ourName: String): Boolean {
        val a = normalize(ministryName)
        val b = normalize(ourName)
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || a.startsWith(b) || b.startsWith(a) ||
            (a.length >= 4 && b.endsWith(a.takeLast(4)) && a.first() == b.first())
    }
}
