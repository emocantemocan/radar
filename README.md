# Radar Navigasyon

[![Derleme](https://github.com/emocantemocan/radar/actions/workflows/android.yml/badge.svg)](https://github.com/emocantemocan/radar/actions/workflows/android.yml)
[![Sürüm](https://img.shields.io/github/v/release/emocantemocan/radar)](https://github.com/emocantemocan/radar/releases)
[![Lisans: MIT](https://img.shields.io/github/license/emocantemocan/radar)](LICENSE)

Türkiye için açık kaynak bir Android navigasyon uygulaması. Rotadaki EDS noktalarını, sabit radarları ve
ortalama hız koridorlarını bilir, yaklaşırken hız sınırını sesli söyler. Android Auto'da çalışır. İsteğe
bağlı olarak Gemini ile uyarı cümleleri üretir ve sesli asistan olarak konuşur.

Uygulama ücretsizdir, reklam içermez, hesap istemez ve kendi sunucusu yoktur. Kaynak kodun tamamı bu
repoda; APK'ler GitHub Actions ile buradaki koddan derlenir.

> Bu uygulama resmî değildir. T.C. İçişleri Bakanlığı, Emniyet Genel Müdürlüğü ya da başka bir kurumla
> bağlantısı yoktur; yalnızca bu kurumların herkese açık yayımladığı verileri kullanır. Uyarılar bilgi
> amaçlıdır ve hiçbir kaynak her radarı kapsamaz. Uygulama olduğu gibi sunulur, geliştiriciler hiçbir
> sorumluluk kabul etmez; ayrıntılar [Sorumluluk reddi](#sorumluluk-reddi) bölümünde.

## Özellikler

### Navigasyon

Adres ve yer aramayı OpenStreetMap Nominatim ile yapar, yol tarifini sesli ve Türkçe okur. Rotadan
çıkınca yeni rota hesaplar ve bildiği radarları hemen yeni rotaya taşır.

Rota servisi ayarlardan seçilir. Varsayılan OSRM ücretsiz ve anahtarsızdır. GraphHopper ücretsiz bir
anahtar ister, Google Routes API ise anahtar ister ve ücretli olabilir. Seçilen servis hata verirse
uygulama OSRM'e geçer ve nedenini kısa bir notla gösterir.

Harita 3D sürüş kamerasıyla gösterilir: gidilen yön hep yukarıdadır, hız arttıkça harita uzaklaşır,
dururken yön telefonun pusulasından alınır. Şehirlerde binalar gerçek yüksekliklerine göre yükselir,
dağlar ve vadiler gölgeyle kabartmalı görünür, araç yola yatık ve gölgeli çizilir. Arazi gölgesi ayardan
kapatılabilir.

### Radar verisi

Rota planlanınca aşağıdaki kaynaklar birlikte sorgulanır ve yalnızca rotanın üstündeki noktalar tutulur.
Aynı yerde birden fazla kaynağın kaydı varsa en güvenilir olanı kalır.

| Kaynak | Ne verir | Not |
|---|---|---|
| [İçişleri Bakanlığı radar ve kontrol noktası sayfası](https://www.icisleri.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari) | Ortalama hız koridorları (giriş, çıkış, sınır) ve günlük radar sayısı | Bakanlık şu an radarların konumunu yayımlamıyor, yalnızca sayısını veriyor. |
| [EGM EDS haritası](https://onlineislemler.egm.gov.tr/trafik/sayfalar/edsharita.aspx) | Yaklaşık 1.460 sabit EDS noktası (hız, kırmızı ışık, kavşak) | Uygulamaya gömülü, haftada bir güncellenir. |
| [OpenStreetMap](https://www.openstreetmap.org/) | Yaklaşık 790 sabit hız kamerası, çoğunda yön ve sınır bilgisi | Uygulamaya gömülü. Karşı şeridi denetleyen kameralar atılır. |
| Kullanıcının kendi bildirimleri | Sürücünün gördüğü seyyar radarlar | Yalnızca o telefonda saklanır, hiçbir yere gönderilmez. |

Bakanlık yanıtları 30 gün telefonda saklanır; servis ulaşılamaz olursa son bilinen veri kullanılır.

### Uyarılar

Her radara 2 km ve 500 m kala sesli uyarı verilir ve ekranda bir kart açılır. Kartın rengi o anki hıza
göre değişir.

Ortalama hız koridorunda ortalama hız canlı hesaplanır. Ortalama sınırı geçerse kalan yol için bir hedef
hız söylenir ("kalan yolu en fazla 95 ile gidin"). Ortalama sınırın altına inince bu da bildirilir.
Koridor takibi navigasyon açıkken çalışır.

Seyyar radar görülen bir yer radar düğmesiyle kaydedilebilir (Android Auto'da "Radar var" düğmesi ya da
asistana sesle). Oradan tekrar geçerken "geçen sefer burada seyyar radar görmüştünüz" uyarısı gelir.
Düğmeye basılı tutmak o noktadaki kaydı siler; 180 günden eski kayıtlar kendiliğinden silinir.

Gemini anahtarı girilirse uyarı cümlelerini Gemini, seçilen tarzda yazar: Kibar, Şakacı, Sert argo
(küfürsüz) ya da Ağzı bozuk. Hız sınırı her cümlede söylenir. Gemini 2,5 saniyede cevap vermezse ya da
internet yoksa düz bir yedek uyarı okunur. Anahtar girilmezse her zaman düz uyarı kullanılır.

### Sesli asistan (isteğe bağlı, Gemini Live)

Mikrofon düğmesiyle açılır ve gerçek zamanlı konuşur. Hızı, kalan yolu, varış saatini, sıradaki radarı
ve koridor ortalamasını bilir; sesle rota değiştirebilir, navigasyonu bitirebilir ve seyyar radar
kaydedebilir. Gemini API anahtarı gerektirir.

### Android Auto

Araba ekranında harita, manevra kartı, hız ve sınır göstergesi, koridor ortalaması, radar listesi,
radar uyarıları ve "Radar var" düğmesi bulunur. Asistan arabanın mikrofonuyla çalışır.

Sıradaki dönüş ve varış bilgisi arabaya da gönderilir; destekleyen arabalar bunu sürücü göstergesinde
ya da head-up display'de gösterir. Göstergede harita gösterebilen arabalarda (Android Auto'nun "cluster"
desteği) harita ve dönüş bilgisi orada da çizilir. Bu destek araba üreticisine ve modeline bağlıdır;
bazı markalar göstergede yalnızca kendi navigasyonunu gösterir.

## Gizlilik ve izinler

Uygulamanın kendi sunucusu yoktur, hesap açtırmaz, reklam ya da analiz kütüphanesi içermez. Veriler
yalnızca şu yerlere gider:

- Konum ve hedef: seçilen rota servisine (OSRM, GraphHopper ya da Google) ve adres araması için
  OpenStreetMap Nominatim'e.
- Rota üzerindeki ilçeler: radar ve koridor sorgusu için İçişleri Bakanlığı'nın herkese açık servisine.
- Harita karoları: OpenFreeMap'e, arazi gölgesi açıksa ekrandaki bölgenin arazi karoları için AWS'deki
  herkese açık Terrain Tiles veri setine.
- Gemini anahtarı girildiyse: uyarı cümlesi için gereken bilgiler (radar türü, mesafe, hız, sınır) ve
  asistanla konuşulanlar Google Gemini API'sine.

API anahtarları ve seyyar radar kayıtları yalnızca telefonda saklanır.

| İzin | Neden |
|---|---|
| Konum (tam ve yaklaşık) | Navigasyon ve radar mesafesi |
| Ön plan servisi (konum) | Ekran kapalıyken navigasyonun sürmesi |
| Bildirim | Navigasyon sürerken kalıcı bildirim |
| Mikrofon | Yalnızca sesli asistan açıldığında |
| İnternet, ağ durumu | Rota, harita ve radar verisi |
| Ses ayarları, uyanık tutma | Sesli uyarıların çalınması, navigasyon sırasında telefonun uyumaması |
| Android Auto şablonları, yüzey, araç hızı | Araba ekranında harita ve göstergenin hızını kullanma |

## Kurulum

Uygulama Play Store'da değil. Bu yüzden Android, APK'yi kurarken "bilinmeyen uygulamaları yükleme" izni
ister.

1. [Releases](https://github.com/emocantemocan/radar/releases/latest) sayfasından `RadarNavigasyon.apk`
   dosyasını indirin.
2. İsterseniz dosyanın değişmediğini aynı sayfadaki `RadarNavigasyon.apk.sha256` ile doğrulayın:
   ```bash
   sha256sum -c RadarNavigasyon.apk.sha256
   ```
3. APK'yi açıp kurun. Yeni sürümler eskisinin üstüne kurulur.
4. Uygulama ilk açılışta konum ve bildirim izni ister. Gemini özellikleri isteniyorsa Ayarlar > Gemini
   API anahtarı alanına anahtar girilir (aşağıda nasıl alınacağı var).

APK'ye güvenmek istemeyenler uygulamayı kaynaktan kendileri derleyebilir (bkz. [Geliştirme](#geliştirme)).

### Android Auto'da görünmesi

Play Store dışından kurulan uygulamaları Android Auto varsayılan olarak gizler:

1. Telefonda Android Auto ayarlarını açıp en alttaki "Sürüm" satırına 10 kez dokunun.
2. Sağ üstteki menüden Geliştirici ayarları > Bilinmeyen kaynaklar seçeneğini açın.
3. Telefonu arabaya bağlayın; "Radar Navigasyon" uygulama listesinde çıkar.

## API anahtarları (isteğe bağlı)

Navigasyon ve radar uyarıları hiçbir anahtar olmadan çalışır. Anahtarlar yalnızca ek özellikler için
gereklidir, telefonda saklanır ve yalnızca ait oldukları servise gönderilir.

### Gemini: uyarı cümleleri ve sesli asistan, ücretsiz katman yeterli

1. <https://aistudio.google.com/apikey> adresine Google hesabıyla girin.
2. "Create API key" ile bir anahtar oluşturup kopyalayın.
3. Ayarlar > Gemini API anahtarı alanına yapıştırın. Uygulama anahtarın erişebildiği en yeni modeli
   kendisi seçer.

### Google Routes API: Google ile rota, ücretli olabilir

1. <https://console.cloud.google.com/> adresinde bir proje oluşturup faturalandırmayı açın. Aylık
   ücretsiz kota kişisel kullanıma genelde yeter. Sürpriz fatura çıkmaması için "Bütçe ve uyarılar"
   bölümünden küçük bir bütçe uyarısı kurmak iyi olur.
2. API'ler ve Hizmetler > Kitaplık bölümünde "Routes API"yi etkinleştirin. Eski Directions API yeni
   projelerde açılmıyor.
3. Kimlik bilgileri > Kimlik bilgisi oluştur > API anahtarı ile anahtar oluşturun ve API
   kısıtlamalarından yalnızca Routes API ile sınırlayın.
4. Ayarlar > Google Maps API anahtarı alanına yapıştırıp Rota servisi olarak Google'ı seçin.

### GraphHopper: rota, ücretsiz

1. <https://graphhopper.com/dashboard/signup> adresinden hesap açın.
2. API Keys > Add API Key ile anahtar oluşturun (günde 500 rota ücretsiz).
3. Ayarlar > GraphHopper API anahtarı alanına girip Rota servisi olarak GraphHopper'ı seçin.

## Ayarlar

| Ayar | Ne işe yarar |
|---|---|
| Gemini API anahtarı | Gemini'nin yazdığı uyarılar ve sesli asistan |
| Konuşma tarzı | Kibar, Şakacı, Sert argo (küfürsüz) ya da Ağzı bozuk |
| Asistan sesi, uyarı sesi | Gemini'nin doğal sesleri ya da telefonun kendi sesi |
| Radar uyarılarını Gemini üretsin | Kapalıysa hep düz uyarı okunur |
| Modeller | Boş bırakılırsa en yeni model kendiliğinden seçilir |
| Rota servisi | OSRM, GraphHopper ya da Google, anahtarlarıyla birlikte |
| Harita | Anahtarsız gece vektör haritası (varsayılan) ya da özel stil veya karo adresi |
| Arazi gölgesi | Dağları ve vadileri kabartmalı gösterir, biraz daha fazla mobil veri kullanır |
| Bildirdiğim seyyar radarları sil | Telefondaki bütün seyyar radar kayıtlarını siler |
| Sürüş simülasyonu | Denemek için aracı rotada sanal olarak ilerletir |

## Bilinen sınırlamalar

- Bakanlık şu an radar konumlarını yayımlamıyor; sabit radarlar EGM ve OpenStreetMap verisinden gelir.
  Seyyar radarlar için herkese açık bir kaynak yok, bu yüzden yalnızca kullanıcının kendi bildirimleri
  kullanılır.
- Uyarılar navigasyon başlatılmışken çalışır; rota açmadan sürerken uyarı gelmez.
- Bakanlık ve EGM verisinde radarın hangi yönü ölçtüğü yok, karşı yöndeki radar için de uyarı gelebilir.
- Herkese açık OSRM sunucusu yoğun saatlerde yavaşlayabilir; düzenli kullanımda GraphHopper daha iyidir.

## Sorumluluk reddi

Uygulama "olduğu gibi" ve hiçbir garanti verilmeden sunulur. Uygulamayı kuran ve kullanan kişi, bu
koşulları kabul etmiş sayılır.

- Geliştiriciler ve katkıcılar, uygulamanın kullanımından ya da kullanılamamasından doğan hiçbir
  zarardan sorumlu değildir. Buna trafik cezaları, kazalar, can ve mal kayıpları, yanlış ya da hiç
  verilmeyen uyarılar, yanlış rota, veri kaybı, mobil veri ücretleri ve üçüncü taraf servislerin (Google
  Cloud, Gemini, GraphHopper vb.) çıkardığı faturalar da dahildir.
- Radar, EDS ve koridor verileri üçüncü taraflardan gelir; eksik, yanlış ya da güncel olmayabilir. Bir
  noktada uyarı gelmemesi orada radar olmadığı anlamına gelmez.
- Uygulama trafik kurallarına uymanın yerine geçmez. Hız sınırlarına ve trafik işaretlerine uymak her
  zaman sürücünün sorumluluğundadır. Sürüş sırasında telefonla uğraşmayın; ayarları ve rotayı yola
  çıkmadan önce yapın.
- Uygulamanın kullanıldığı yerdeki yasalara uygun kullanılmasından kullanıcı sorumludur.
- Uygulama İçişleri Bakanlığı, Emniyet Genel Müdürlüğü, Google ya da başka bir kurum veya şirket
  tarafından onaylanmamış, desteklenmemiştir; bu kurumlarla hiçbir bağı yoktur.
- Uygulamada Gemini'nin ürettiği cümleler yapay zekâ çıktısıdır; seçilen tarza göre argo ya da küfür
  içerebilir ve yanlış bilgi verebilir.

Lisansın kendisi de aynı garanti ve sorumluluk reddini içerir (bkz. [LICENSE](LICENSE)).

## Geliştirme

```
core/   Android'den bağımsız iş mantığı (Kotlin/JVM, birim testli)
        ministry/  Bakanlık API istemcisi, ilçe dizini, rotadaki radar ve koridorları bulma
        egm/       EGM EDS noktaları
        osm/       OpenStreetMap hız kameraları
        report/    Seyyar radar bildirimleri (telefonda)
        routing/   OSRM, GraphHopper, Google Routes + Türkçe yönlendirme metinleri
        nav/       GPS'i rotaya oturtma, manevra anonsları, rotadan çıkma
        alert/     Uyarı motoru, koridor ortalama hız ve hedef hız, Gemini istemleri
        gemini/    Gemini metin, ses (TTS) ve Live (WebSocket) istemcileri
app/    Android uygulaması
        ui/        Telefon arayüzü (MapLibre harita, ayarlar)
        car/       Android Auto (NavigationTemplate, harita, ekranlar)
        nav/       Navigasyon denetleyicisi, ön plan servisi, uyarı seslendirme
        assistant/ Gemini Live asistanı ve araçları
scripts/ Gömülü EGM ve OSM verisini yenileyen betikler
```

```bash
./gradlew :core:test                      # Android SDK gerekmez
./gradlew :app:testDebugUnitTest          # Android Auto ekran testleri (SDK gerekir)
./gradlew :app:assembleDebug              # APK (SDK gerekir: ANDROID_HOME ya da local.properties)
```

JDK 17 ve Android SDK 35 gerekir. Android Auto bilgisayarda
[Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu) ile denenebilir.

## Katkı

Hata bildirimi ve öneriler için [Issues](https://github.com/emocantemocan/radar/issues) sayfası
kullanılabilir. Kod katkısı için repoyu fork'layıp pull request açmak yeterli; her pull request'te
GitHub Actions testleri çalıştırıp APK derler.

## Lisans

Kod [MIT lisansı](LICENSE) ile dağıtılır.

- Harita verisi © OpenStreetMap katkıcıları (ODbL), vektör karolar © OpenFreeMap / OpenMapTiles.
- Arazi verisi: Mapzen Terrain Tiles (AWS Open Data); kaynakları arasında SRTM, GMTED2010 ve ETOPO1 var.
- Harita motoru MapLibre Native (BSD-2).
- Yazı tipleri Space Grotesk ve Inter, SIL Open Font License 1.1 (`third_party/fonts/`).
- Release APK'leri güncellemelerin üstüne kurulabilmesi için repodaki sabit anahtarla
  (`app/debug.keystore`) imzalanır. Kendi dağıtımını yapacak olanlar kendi imza anahtarını kullanmalıdır.
