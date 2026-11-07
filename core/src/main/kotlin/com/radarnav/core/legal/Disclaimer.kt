package com.radarnav.core.legal

/**
 * İlk açılışta gösterilen kullanım koşulları / sorumluluk reddi. Telefon ve Android Auto ortak kullanır.
 * Metin anlamca değişirse [VERSION] artırılır; kullanıcıdan yeniden onay istenir.
 */
object Disclaimer {
    const val VERSION = 1

    const val TITLE = "Kullanmadan önce"

    val TEXT = """
        Bu uygulama devletin resmî uygulaması değildir.

        Radar bilgileri her zaman doğru ya da eksiksiz olmayabilir. Uyarı gelmemesi orada radar olmadığı anlamına gelmez.

        Hız sınırına ve trafik kurallarına uymak senin sorumluluğundadır. Araba kullanırken telefonla uğraşma.

        Uygulama hiçbir garanti verilmeden sunulur. Kullanırken ceza, kaza ya da başka bir zarar olursa uygulamayı yapanlar sorumlu değildir.

        Gemini'nin söylediği uyarılar yapay zekâ tarafından yazılır; bazen yanlış ya da kaba olabilir.

        "Kabul ediyorum"a basarak bunları kabul etmiş olursun.
    """.trimIndent()

    const val ACCEPT = "Kabul ediyorum"
    const val DECLINE = "Kabul etmiyorum"

    /** Android Auto'da: koşullar telefonda kabul edilmeden navigasyon açılmaz. */
    const val CAR_MESSAGE = "Önce telefonda Radar Navigasyon'u aç ve çıkan uyarıyı kabul et."

    fun isAccepted(acceptedVersion: Int): Boolean = acceptedVersion >= VERSION
}
