package com.radarnav.core.legal

/**
 * İlk açılışta gösterilen kullanım koşulları / sorumluluk reddi. Telefon ve Android Auto ortak kullanır.
 * Metin anlamca değişirse [VERSION] artırılır; kullanıcıdan yeniden onay istenir.
 */
object Disclaimer {
    const val VERSION = 1

    const val TITLE = "Kullanmadan önce"

    val TEXT = """
        Radar Navigasyon resmî bir uygulama değildir. İçişleri Bakanlığı, Emniyet Genel Müdürlüğü ya da başka bir kurumla bağı yoktur.

        Radar ve koridor verileri üçüncü taraflardan gelir; eksik, yanlış ya da eski olabilir. Bir yerde uyarı gelmemesi orada radar olmadığı anlamına gelmez.

        Hız sınırlarına ve trafik kurallarına uymak her zaman sürücünün sorumluluğundadır. Sürerken telefonla uğraşmayın, ayarları yola çıkmadan yapın.

        Uygulama olduğu gibi, hiçbir garanti verilmeden sunulur. Geliştiriciler; trafik cezaları, kazalar, can ve mal kayıpları, yanlış ya da hiç verilmeyen uyarılar, yanlış rota, mobil veri ve üçüncü taraf servis ücretleri dahil, kullanımdan doğan hiçbir zarardan sorumlu değildir.

        Gemini'nin ürettiği cümleler yapay zekâ çıktısıdır; seçilen tarza göre argo ya da küfür içerebilir ve yanlış bilgi verebilir.

        Devam ederek bu koşulları kabul etmiş olursunuz.
    """.trimIndent()

    const val ACCEPT = "Kabul ediyorum"
    const val DECLINE = "Kabul etmiyorum"

    /** Android Auto'da: koşullar telefonda kabul edilmeden navigasyon açılmaz. */
    const val CAR_MESSAGE = "Radar Navigasyon'u kullanmadan önce telefondaki uygulamayı açıp kullanım koşullarını kabul edin."

    fun isAccepted(acceptedVersion: Int): Boolean = acceptedVersion >= VERSION
}
