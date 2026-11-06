package com.radarnav.core.alert

import com.radarnav.core.model.Radar
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import kotlin.math.roundToInt

/** Uyarıların ve asistanın konuşma tarzı (ayarlardan seçilir). */
enum class Persona(val key: String, val title: String, val style: String) {
    KIBAR(
        "kibar", "Kibar",
        "Kibar, sakin ve nazik bir yardımcı pilotsun. Argo ve küfür kullanma; nazikçe uyar.",
    ),
    SAKACI(
        "sakaci", "Şakacı",
        "Esprili, takılan, iğneleyici bir yol arkadaşısın. Laf sok, dalga geç, espri yap ama dilin temiz olsun; " +
            "argo ve küfür kullanma.",
    ),
    SERT_ARGO(
        "sert", "Sert argo (küfürsüz)",
        "Sokak ağzıyla konuşan, söylenen, laf sokan, bol argolu, yılların İstanbul taksicisisin. " +
            "'Abi', 'birader', 'koçum', 'yavaş be', 'gaza gelme' gibi ifadeler kullan, söylen, homurdan. " +
            "Argo serbest ama küfür etme.",
    ),
    AGZI_BOZUK(
        "agzi_bozuk", "Ağzı bozuk",
        "Ağzı bozuk, sinirli, sürekli söylenen bir dolmuş şoförüsün. Ağır argo ve hafif küfürler serbest. " +
            "Ama nefret söylemi, ırkçılık, cinsiyetçilik, aileye ağır hakaret ve tehdit kesinlikle yok.",
    );

    companion object {
        fun fromKey(key: String?): Persona = entries.firstOrNull { it.key == key } ?: SERT_ARGO
    }
}

/** Gemini için uyarı istemleri ve Gemini yokken okunacak düz yedek cümleler. */
object WarningText {

    private const val RULES =
        "Kurallar: Sadece sürücüye sesli okunacak metni yaz; tırnak, emoji, başlık, açıklama yok. " +
            "En fazla iki kısa cümle, toplam 30 kelimeyi geçme. Verilen mesafeyi ve hız sınırını mutlaka söyle, " +
            "sayıları rakamla yaz (ör. 82). Sürücünün hızı sınırın üzerindeyse çok daha sert ol ve yavaşlamasını iste; " +
            "altındaysa yine de dikkatli olmasını söyle. Amacın sürücüyü yavaşlatmak ve kurala uydurmak: " +
            "asla hız yapmayı, radardan kaçmayı ya da cezadan kurtulma hilesini önerme. Türkçe yaz."

    fun systemInstruction(persona: Persona): String =
        "Sen Türkiye'de araç kullanan bir sürücünün sesli navigasyon asistanısın. ${persona.style} $RULES"

    private fun kindName(e: AlertEvent): String = when (e.hazard.hazard) {
        is Radar -> "radar (hız kamerası)"
        is SpeedCorridor -> "ortalama hız koridoru"
    }

    private fun speedText(speed: Double) = "${speed.roundToInt()} km/s"

    /** Gemini'ye gönderilecek kullanıcı mesajı. [recent] tekrar etmemesi için son cümleler. */
    fun userPrompt(e: AlertEvent, recent: List<String>): String {
        val limit = e.hazard.hazard.speedLimit
        val limitText = limit?.let { "Hız sınırı $it km/s." } ?: "Hız sınırı bilinmiyor; sınırı uydurma."
        val place = listOf(e.hazard.hazard.province, e.hazard.hazard.district)
            .filter { it.isNotBlank() }.joinToString(" / ").lowercase(TurkishInstructions.TR)
        val situation = when (e) {
            is AlertEvent.Approach, is AlertEvent.Prefetch -> {
                val (dist, speed, stage) = when (e) {
                    is AlertEvent.Approach -> Triple(e.distance, e.speedKmh, e.stage)
                    is AlertEvent.Prefetch -> Triple(
                        if (e.stage == Stage.FAR) 2000.0 else 500.0, e.speedKmh, e.stage,
                    )
                    else -> error("unreachable")
                }
                val distText = TurkishInstructions.spokenDistance(roundDistance(dist, stage))
                val over = limit != null && speed > limit
                "${distText} ileride ${kindName(e)} var. $limitText Sürücünün şu anki hızı ${speedText(speed)}" +
                    (if (over) " yani sınırın üzerinde." else ".") +
                    (if (stage == Stage.NEAR) " Bu son uyarı, kamera çok yakın." else " Bu ilk uyarı.")
            }
            is AlertEvent.CorridorEntered ->
                "Sürücü az önce ${kindName(e)}na girdi. $limitText Koridor boyunca ortalama hız ölçülecek. " +
                    "Şu anki hızı ${speedText(e.speedKmh)}."
            is AlertEvent.CorridorOverAverage ->
                "Sürücü ortalama hız koridorunda, ortalaması ${speedText(e.averageKmh)}. $limitText " +
                    "Koridorun bitmesine ${TurkishInstructions.spokenDistance(e.remainingMeters)} var. Ortalamayı düşürmesi lazım."
            is AlertEvent.CorridorExited ->
                "Sürücü ortalama hız koridorundan çıktı. Ortalaması ${speedText(e.averageKmh)}. $limitText " +
                    "Kısaca koridorun bittiğini söyle ve ortalamasına göre takıl ya da tebrik et."
        }
        val history = if (recent.isEmpty()) "" else
            "\nDaha önce şunları söyledin, aynı kalıpları tekrarlama: " + recent.joinToString(" | ")
        val where = if (place.isNotBlank()) " Konum: $place." else ""
        return situation + where + history
    }

    /** Gemini yoksa ya da geç kalırsa okunacak düz cümle. */
    fun fallback(e: AlertEvent): String {
        val limit = e.hazard.hazard.speedLimit
        val limitText = limit?.let { " Hız sınırı $it." } ?: ""
        return when (e) {
            is AlertEvent.Approach, is AlertEvent.Prefetch -> {
                val (dist, stage) = when (e) {
                    is AlertEvent.Approach -> e.distance to e.stage
                    is AlertEvent.Prefetch -> e.distance to e.stage
                    else -> error("unreachable")
                }
                val what = if (e.hazard.isCorridor) "Hız koridoru" else "Radar"
                "$what ${TurkishInstructions.spokenDistance(roundDistance(dist, stage))} ileride.$limitText"
            }
            is AlertEvent.CorridorEntered -> "Hız koridoruna girdiniz.$limitText"
            is AlertEvent.CorridorOverAverage ->
                "Koridordaki ortalama hızınız ${e.averageKmh.roundToInt()}.$limitText Yavaşlayın."
            is AlertEvent.CorridorExited -> "Hız koridoru bitti. Ortalamanız ${e.averageKmh.roundToInt()}."
        }
    }

    /** "1.870 m" yerine kulağa doğal gelen yuvarlak mesafe. */
    fun roundDistance(d: Double, stage: Stage): Double = when (stage) {
        Stage.FAR -> if (d > 1500) 2000.0 else if (d > 750) 1000.0 else 500.0
        Stage.NEAR -> if (d > 350) 500.0 else if (d > 150) 200.0 else 100.0
    }

    /** Gemini çıktısını okunacak hale getirir (tırnak, yıldız vb. temizler). */
    fun clean(raw: String): String = raw
        .replace(Regex("[*_#`\"“”]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
