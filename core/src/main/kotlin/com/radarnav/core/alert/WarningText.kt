package com.radarnav.core.alert

import com.radarnav.core.model.HazardSource
import com.radarnav.core.model.Radar
import com.radarnav.core.model.SpeedCorridor
import com.radarnav.core.routing.TurkishInstructions
import kotlin.math.floor
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
            "sayıları rakamla yaz (ör. 82). Sertliği sana verilen kademeye göre ayarla ama kişiliğinin dil " +
            "sınırlarını asla aşma. Amacın sürücüyü yavaşlatmak ve kurala uydurmak: " +
            "asla hız yapmayı, radardan kaçmayı ya da cezadan kurtulma hilesini önerme. Türkçe yaz."

    fun systemInstruction(persona: Persona): String =
        "Sen Türkiye'de araç kullanan bir sürücünün sesli navigasyon asistanısın. ${persona.style} $RULES"

    /** Kademeye göre Gemini'ye verilen ton talimatı. */
    fun toneInstruction(severity: Severity): String = when (severity) {
        Severity.CALM -> "Sertlik kademesi 1/4: sürücü kurallara uyuyor. Sakin, kısa bir hatırlatma yap; " +
            "istersen küçük bir övgü ekle. Sertleşme."
        Severity.LIGHT -> "Sertlik kademesi 2/4: sürücü sınırın hemen üstünde. Hafifçe takıl, " +
            "şakayla karışık yavaşlamasını iste."
        Severity.SASSY -> "Sertlik kademesi 3/4: sürücü sınırı belirgin şekilde aşıyor. Laf sok, söylen, " +
            "iğnele ve net bir şekilde yavaşlamasını iste."
        Severity.HARSH -> "Sertlik kademesi 4/4: sürücü sınırı çok aşıyor. Bağırır gibi, kişiliğinin izin " +
            "verdiği en sert hâlinle, kısa ve acil cümlelerle hemen yavaşlamasını iste."
    }

    /** Olayın anlık (ya da koridorda ortalama) hıza göre sertliği. */
    fun severityOf(e: AlertEvent): Severity {
        val limit = e.hazard.hazard.speedLimit
        return when (e) {
            is AlertEvent.Approach -> Severity.of(e.speedKmh, limit).let {
                // 200 m uyarısı yalnızca hız aşımında gelir; en az "laf sokma" kademesi
                if (e.stage == Stage.FINAL && it < Severity.SASSY) Severity.SASSY else it
            }
            is AlertEvent.Prefetch -> Severity.of(e.speedKmh, limit)
            is AlertEvent.CorridorEntered -> Severity.of(e.speedKmh, limit)
            is AlertEvent.CorridorOverAverage -> Severity.of(e.averageKmh, limit)
            is AlertEvent.CorridorExited -> Severity.of(e.averageKmh, limit)
            is AlertEvent.CorridorBackUnder -> Severity.CALM
        }
    }

    private fun kindName(e: AlertEvent): String = when (val h = e.hazard.hazard) {
        is Radar -> if (h.source == HazardSource.USER) "seyyar radar noktası" else "radar (hız kamerası)"
        is SpeedCorridor -> "ortalama hız koridoru"
    }

    private fun speedText(speed: Double) = "${speed.roundToInt()} km/s"

    private fun stageOf(e: AlertEvent): Pair<Double, Stage>? = when (e) {
        is AlertEvent.Approach -> e.distance to e.stage
        is AlertEvent.Prefetch -> (if (e.stage == Stage.FAR) 2000.0 else 500.0) to e.stage
        else -> null
    }

    /** Hedef hız bunun altındaysa pratikte kurtarılamaz (otoyolda 40'la gitmek de tehlikeli). */
    const val MIN_SENSIBLE_TARGET = 50.0

    /** Hedef hızı aşağı, 5'in katına yuvarla: "en fazla 97" yerine "en fazla 95". */
    fun floorTo5(v: Double): Int = (floor(v / 5.0) * 5).toInt()

    private fun targetSentence(e: AlertEvent.CorridorOverAverage): String {
        val t = e.targetKmh
        return when {
            t == null || t < MIN_SENSIBLE_TARGET ->
                "Kalan yolda ortalamayı sınıra indirmek artık mümkün değil; yine de yavaşlamasını, ortalamayı olabildiğince düşürmesini söyle."
            else -> "Kalan yolu en fazla ${floorTo5(t)} km/s ile giderse ortalaması sınıra iner."
        }
    }

    /** Gemini'ye gönderilecek kullanıcı mesajı. [recent] tekrar etmemesi için son cümleler. */
    fun userPrompt(e: AlertEvent, recent: List<String>, severity: Severity = severityOf(e)): String {
        val limit = e.hazard.hazard.speedLimit
        val limitText = limit?.let { "Hız sınırı $it km/s." } ?: "Hız sınırı bilinmiyor; sınırı uydurma."
        val place = listOf(e.hazard.hazard.province, e.hazard.hazard.district)
            .filter { it.isNotBlank() }.joinToString(" / ").lowercase(TurkishInstructions.TR)
        val situation = when (e) {
            is AlertEvent.Approach, is AlertEvent.Prefetch -> {
                val (dist, stage) = stageOf(e)!!
                val speed = if (e is AlertEvent.Approach) e.speedKmh else (e as AlertEvent.Prefetch).speedKmh
                val distText = TurkishInstructions.spokenDistance(roundDistance(dist, stage))
                val overBy = limit?.let { (speed - it).roundToInt() }
                "$distText ileride ${kindName(e)} var. $limitText Sürücünün şu anki hızı ${speedText(speed)}" +
                    (if (overBy != null && overBy > 0) ", yani sınırı $overBy km/s aşıyor." else ".") +
                    when (stage) {
                        Stage.FAR -> " Bu ilk uyarı."
                        Stage.NEAR -> " Bu ikinci uyarı, kamera yakın."
                        Stage.FINAL -> " Bu son uyarı: daha önce uyardın ama hâlâ yavaşlamadı, kamera dibinde."
                    }
            }
            is AlertEvent.CorridorEntered ->
                "Sürücü az önce ${kindName(e)}na girdi. $limitText Koridor boyunca ortalama hız ölçülecek. " +
                    "Şu anki hızı ${speedText(e.speedKmh)}. Ortalamasını takip ettiğini ve sınırı aşarsa uyaracağını söyle."
            is AlertEvent.CorridorOverAverage ->
                "Sürücü ortalama hız koridorunda, ortalaması ${speedText(e.averageKmh)}. $limitText " +
                    "Koridorun bitmesine ${TurkishInstructions.spokenDistance(e.remainingMeters)} var. " +
                    targetSentence(e) + " Hedef hızı mutlaka söyle."
            is AlertEvent.CorridorBackUnder ->
                "Sürücünün koridordaki ortalaması yeniden sınırın altına indi: ${speedText(e.averageKmh)}. $limitText " +
                    "Koridorun bitmesine ${TurkishInstructions.spokenDistance(e.remainingMeters)} var. " +
                    "Kısaca ortalamanın düzeldiğini söyle, böyle devam etmesini iste."
            is AlertEvent.CorridorExited ->
                "Sürücü ortalama hız koridorundan çıktı. Ortalaması ${speedText(e.averageKmh)}. $limitText " +
                    "Kısaca koridorun bittiğini söyle ve ortalamasına göre takıl ya da tebrik et."
        }
        val history = if (recent.isEmpty()) "" else
            "\nDaha önce şunları söyledin, aynı kalıpları tekrarlama: " + recent.joinToString(" | ")
        val where = if (place.isNotBlank()) " Konum: $place." else ""
        val user = (e.hazard.hazard as? Radar)?.takeIf { it.source == HazardSource.USER }?.let { r ->
            val age = r.reportedAt?.let { com.radarnav.core.report.UserReportStore.ageText(it, System.currentTimeMillis()) } ?: "daha önce"
            " Not: burası sabit kamera değil; sürücü $age burada seyyar radar gördüğünü kendisi bildirmişti" +
                (if (r.reportCount > 1) " (${r.reportCount} kez)" else "") +
                ". 'Geçen sefer burada seyyar radar vardı' anlamında söyle, bugün kesin varmış gibi konuşma."
        } ?: ""
        val passive = if (!e.hazard.hazard.active) {
            " Not: bakanlık verisinde bu nokta şu an pasif görünüyor; yine de kamera olabileceğini söyle."
        } else ""
        return situation + where + passive + user + "\n" + toneInstruction(severity) + history
    }

    /** Gemini yoksa ya da geç kalırsa okunacak düz cümle (sertliği hıza göre değişir). */
    fun fallback(e: AlertEvent): String {
        val limit = e.hazard.hazard.speedLimit
        val severity = severityOf(e)
        return when (e) {
            is AlertEvent.Approach, is AlertEvent.Prefetch -> {
                val (dist, stage) = stageOf(e)!!
                val d = if (e is AlertEvent.Approach) e.distance else dist
                val speed = if (e is AlertEvent.Approach) e.speedKmh else (e as AlertEvent.Prefetch).speedKmh
                val isUser = e.hazard.source == HazardSource.USER
                val what = if (e.hazard.isCorridor) "Hız koridoru" else if (isUser) "Seyyar radar noktası" else "Radar"
                val distText = TurkishInstructions.spokenDistance(roundDistance(d, stage))
                if (isUser) return "$distText ileride, geçen sefer seyyar radar gördüğün yer var." +
                    (if (severity >= Severity.SASSY) " Yavaşla." else "")
                val limitText = limit?.let { " Hız sınırı $it." } ?: ""
                when (severity) {
                    Severity.CALM -> "$what $distText ileride.$limitText"
                    Severity.LIGHT -> "$what $distText ileride.$limitText Biraz yavaşlayın."
                    Severity.SASSY -> "$what $distText ileride! Hızınız ${speed.roundToInt()}, sınır $limit. Yavaşlayın."
                    Severity.HARSH -> "Dikkat! $what $distText ileride! Hızınız ${speed.roundToInt()}, sınır $limit. Hemen yavaşlayın!"
                }
            }
            is AlertEvent.CorridorEntered ->
                "Hız koridoruna girdiniz." + (limit?.let { " Hız sınırı $it." } ?: "") + " Ortalamanızı takip ediyorum."
            is AlertEvent.CorridorOverAverage ->
                "Koridordaki ortalama hızınız ${e.averageKmh.roundToInt()}." + (limit?.let { " Sınır $it." } ?: "") +
                    (e.targetKmh?.takeIf { it >= MIN_SENSIBLE_TARGET }?.let { " Kalan yolu en fazla ${floorTo5(it)} ile gidin." }
                        ?: if (severity == Severity.HARSH) " Hemen yavaşlayın!" else " Yavaşlayın.")
            is AlertEvent.CorridorExited -> "Hız koridoru bitti. Ortalamanız ${e.averageKmh.roundToInt()}."
            is AlertEvent.CorridorBackUnder -> "Tamam, koridor ortalamanız ${e.averageKmh.roundToInt()}'e indi. Böyle devam."
        }
    }

    /**
     * Gemini seslendirmesi (TTS) için ton yönergesi: kişilik + anlık hıza göre sertlik.
     * [com.radarnav.core.gemini.GeminiTts.prompt] bunu etiketli ayrı satıra koyar; model yönergeyi
     * okumaz, cümleyi bu tonda söyler. İngilizce yönerge modellerde daha tutarlı çalışıyor (canlı testle ölçüldü).
     */
    fun voiceStyle(persona: Persona, severity: Severity): String {
        val who = when (persona) {
            Persona.KIBAR -> "calm, polite co-pilot"
            Persona.SAKACI -> "playful, teasing friend, smiling"
            Persona.SERT_ARGO -> "streetwise, grumbling Istanbul taxi driver"
            Persona.AGZI_BOZUK -> "irritated, foul-mouthed minibus driver, muttering"
        }
        val tone = when (severity) {
            Severity.CALM -> "relaxed tone"
            Severity.LIGHT -> "lightly teasing"
            Severity.SASSY -> "sarcastic, slightly loud"
            Severity.HARSH -> "shouting, very urgent and fast"
        }
        return "$who; $tone"
    }

    /** Yol tarifi için sakin, net navigasyon sesi. */
    const val GUIDANCE_STYLE = "clear, calm navigation voice"

    /** "1.870 m" yerine kulağa doğal gelen yuvarlak mesafe. */
    fun roundDistance(d: Double, stage: Stage): Double = when (stage) {
        Stage.FAR -> if (d > 1500) 2000.0 else if (d > 750) 1000.0 else 500.0
        Stage.NEAR -> if (d > 350) 500.0 else if (d > 150) 200.0 else 100.0
        Stage.FINAL -> if (d > 150) 200.0 else 100.0
    }

    /**
     * Hız sınırı uyarının olmazsa olmazı: Gemini cümleye koymayı unuttuysa (canlı testte "Kibar"
     * kişilikte görüldü) sona eklenir. Rakamla ya da "seksen iki" gibi yazıyla geçiyorsa dokunulmaz.
     */
    fun ensureLimit(text: String, limit: Int?): String {
        if (limit == null) return text
        if (Regex("(?<!\\d)$limit(?!\\d)").containsMatchIn(text)) return text
        if (text.lowercase(TurkishInstructions.TR).contains(numberWords(limit))) return text
        val sep = if (text.trimEnd().endsWith('.') || text.trimEnd().endsWith('!') || text.trimEnd().endsWith('?')) " " else ". "
        return text.trimEnd() + sep + "Sınır $limit."
    }

    private val ONES = listOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
    private val TENS = listOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")

    /** 82 -> "seksen iki", 110 -> "yüz on". */
    internal fun numberWords(n: Int): String {
        val h = n / 100
        val t = (n % 100) / 10
        val o = n % 10
        return listOfNotNull(
            if (h == 1) "yüz" else if (h > 1) "${ONES[h]} yüz" else null,
            TENS[t].ifEmpty { null },
            ONES[o].ifEmpty { null },
        ).joinToString(" ")
    }

    /** Gemini çıktısını okunacak hale getirir (tırnak, yıldız vb. temizler). */
    fun clean(raw: String): String = raw
        .replace(Regex("[*_#`\"“”]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
