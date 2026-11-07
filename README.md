# Radar Navigasyon

Türkiye için açık kaynak bir Android navigasyon uygulaması. Rotandaki EDS noktalarını, sabit radarları ve
ortalama hız koridorlarını bilir. Yaklaşırken hız sınırını söyler, hızlı gidiyorsan laf sokup yavaşlatır.
Android Auto'da çalışır, içinde Gemini ile sesli konuşabileceğin bir asistan var.

[Son sürümü indir (APK)](../../releases/latest)

> Uyarılar bilgi amaçlıdır ve hiçbir kaynak her radarı kapsamaz. Kurallara uymak sürücünün sorumluluğunda.
> Uygulamanın amacı seni sınırın altında tutmak.

## Neler yapar?

### Navigasyon

Adres ve yer aramayı OpenStreetMap Nominatim ile yapar, yol tarifini sesli ve Türkçe okur. Rotadan
çıkarsan yeni rota hesaplar ve bildiği radarları hemen yeni rotaya taşır.

Rota servisini ayarlardan seçersin. Varsayılan OSRM ücretsiz ve anahtarsız. GraphHopper ücretsiz bir
anahtar ister, Google Routes API ise anahtar ister ve ücretli olabilir. Seçtiğin servis hata verirse
uygulama OSRM'e geçer ve nedenini kısa bir notla yazar.

Harita 3D sürüş kamerasıyla gösterilir. Gittiğin yön hep yukarıdadır, ok yola oturur, hızlandıkça
harita uzaklaşır. Dururken yönü telefonun pusulasından alır.

### Radarlar nereden geliyor?

Rota planlanınca bütün kaynaklar birlikte sorgulanır ve yalnızca rotanın üstündeki noktalar tutulur.
Aynı yerde birden fazla kaynağın kaydı varsa en güvenilir olanı kalır.

| Kaynak | Ne verir | Not |
|---|---|---|
| [İçişleri Bakanlığı](https://www.icisleri.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari) | Ortalama hız koridorları (giriş, çıkış, sınır) ve günlük radar sayısı | Bakanlık şu an radarların konumunu paylaşmıyor, yalnızca sayısını veriyor. Konum gelirse uygulama kullanır. |
| [EGM EDS haritası](https://onlineislemler.egm.gov.tr/trafik/sayfalar/edsharita.aspx) | Yaklaşık 1.460 sabit EDS noktası (hız, kırmızı ışık, kavşak) | Uygulamaya gömülü, haftada bir arka planda güncellenir. |
| OpenStreetMap | Yaklaşık 790 sabit hız kamerası, çoğunda yön ve sınır bilgisi var | Uygulamaya gömülü. Karşı şeridi denetleyen kameralar atılır. |
| Senin bildirimlerin | Gördüğün seyyar radarlar | Yalnızca telefonunda durur, hiçbir sunucuya gitmez. |

Bakanlığın yanıtları 30 gün telefonda saklanır, servis çökerse son bilinen veri kullanılır.

### Uyarılar

Her radara 2 km ve 500 m kala sesli uyarı verir, ekranda bir kart açar. Kartın rengi hızına göre değişir.

Ortalama hız koridoruna girince haber verir ve ortalamanı canlı hesaplar. Ortalaman sınırı geçerse
kalan yol için bir hedef hız söyler ("kalan yolu en fazla 95 ile git"). Ortalama sınırın altına inince
"tamam, böyle devam" der. Koridor takibi navigasyon açıkken çalışır.

Seyyar radar gördüğünde radar düğmesine dokunursun. Android Auto'da bunun karşılığı "Radar var"
düğmesi, ya da asistana "burada radar var" demen yeterli. Oradan bir daha geçtiğinde "geçen sefer
burada seyyar radar görmüştün" der. Düğmeye basılı tutarsan o noktadaki kayıt silinir. 180 günden eski
kayıtları uygulama kendisi unutur.

Uyarı cümlelerini Gemini, seçtiğin tarzda yazar: Kibar, Şakacı, Sert argo (küfürsüz) ya da Ağzı bozuk.
Hızlıysan daha sert konuşur ve hız sınırını her seferinde söyler. Gemini 2,5 saniyede cevap vermezse
ya da internet yoksa düz bir yedek uyarı okunur.

### Sesli asistan (Gemini Live)

Mikrofon düğmesiyle açılır. Gerçek zamanlı konuşursun, lafını kesebilirsin. Hızını, kalan yolu,
varış saatini, sıradaki radarı ve koridor ortalamanı bilir. Sesle rota değiştirebilir ("beni Bolu'ya
götür"), navigasyonu bitirebilir ve seyyar radar kaydedebilir.

### Android Auto

Araba ekranında harita, manevra kartı, hız ve sınır göstergesi, koridor ortalaması ve radar listesi
var. Radar uyarıları orada da çıkar. "Radar var" düğmesi de orada, asistan da arabanın mikrofonuyla çalışır.

## Kurulum

1. [Releases](../../releases/latest) sayfasından `RadarNavigasyon.apk` dosyasını telefona indir.
2. Dosyayı açıp kur. Telefon "bilinmeyen uygulamaları yükleme" izni ister. Yeni sürümler eskisinin
   üstüne kurulur.
3. Uygulamayı açıp konum ve bildirim izni ver. Asistanı kullanacaksan mikrofon izni de gerekir.
4. Ayarlar > Gemini API anahtarı alanına anahtarını gir (ücretsiz, nasıl alınacağı aşağıda).
   Anahtar olmadan da navigasyon ve radar uyarıları çalışır, sadece asistan ve Gemini'nin yazdığı
   uyarılar olmaz.

Her commit'in derlemesi [Actions](../../actions) sekmesinde de duruyor, oradan indirmek için GitHub
hesabıyla giriş yapmak gerekiyor.

### Android Auto'da görünmesi için

Play Store dışından kurulan uygulamaları Android Auto varsayılan olarak gizler:

1. Telefonda Android Auto ayarlarını aç ve en alttaki "Sürüm" satırına 10 kez dokun.
2. Sağ üstteki menüden Geliştirici ayarları > Bilinmeyen kaynaklar seçeneğini aç.
3. Telefonu arabaya bağla. "Radar Navigasyon" uygulama listesinde çıkar.

## API anahtarları

Ayarlar ekranında her anahtarın altında "nasıl alınır?" bağlantısı var. Anahtarlar yalnızca telefonda
saklanır, listede son 4 karakterleri görünür.

### Gemini (asistan ve uyarı cümleleri), ücretsiz

1. <https://aistudio.google.com/apikey> adresine Google hesabınla gir.
2. "Create API key" ile bir anahtar oluşturup kopyala.
3. Ayarlar > Gemini API anahtarı alanına yapıştır. Model seçmen gerekmiyor, uygulama anahtarının
   erişebildiği en yeni modeli kendisi seçer.

### Google Routes API (Google ile rota), ücretli olabilir

1. <https://console.cloud.google.com/> adresinde bir proje oluştur ve faturalandırmayı aç. Aylık
   ücretsiz kota kişisel kullanıma genelde yeter. "Bütçe ve uyarılar" bölümünden küçük bir bütçe
   uyarısı kurmanı öneririm.
2. API'ler ve Hizmetler > Kitaplık bölümünde "Routes API"yi bulup etkinleştir. Eski Directions API
   yeni projelerde açılmıyor, uygulama Routes API'yi kullanıyor.
3. Kimlik bilgileri > Kimlik bilgisi oluştur > API anahtarı ile anahtar oluştur.
4. Anahtarı API kısıtlamalarından yalnızca Routes API ile sınırla. "Android uygulamaları"
   kısıtlamasını seçme, çünkü uygulama anahtarı doğrudan REST üzerinden kullanıyor.
5. Ayarlar > Google Maps API anahtarı alanına yapıştır ve Rota servisi olarak Google'ı seç.

Routes API açık değilse rota ekranında "Google anahtarında Routes API açık değil" yazar ve uygulama
OSRM ile devam eder.

### GraphHopper (rota), ücretsiz

1. <https://graphhopper.com/dashboard/signup> adresinden hesap aç.
2. API Keys > Add API Key ile anahtar oluştur. Günde 500 rota ücretsiz.
3. Anahtarı Ayarlar > GraphHopper API anahtarı alanına gir ve Rota servisi olarak GraphHopper'ı seç.

## Ayarlar

| Ayar | Ne işe yarar |
|---|---|
| Gemini API anahtarı | Sesli asistan ve Gemini'nin yazdığı uyarılar |
| Konuşma tarzı | Kibar, Şakacı, Sert argo (küfürsüz) ya da Ağzı bozuk |
| Asistan sesi, uyarı sesi | Gemini'nin doğal sesleri ya da telefonun kendi sesi |
| Radar uyarılarını Gemini üretsin | Kapalıysa hep düz uyarı okunur |
| Modeller | Boş bırakılırsa en yeni model kendiliğinden seçilir |
| Rota servisi | OSRM, GraphHopper ya da Google, anahtarlarıyla birlikte |
| Harita | Anahtarsız gece vektör haritası (varsayılan) ya da kendi stil veya karo adresin |
| Bildirdiğim seyyar radarları sil | Telefondaki bütün seyyar radar kayıtlarını siler |
| Sürüş simülasyonu | Evde denemek için aracı rotada sanal olarak ilerletir |

## Gizlilik

Uygulamanın kendi sunucusu yok, hesap açtırmaz, seni takip etmez. Konumun yalnızca seçtiğin rota
servisine (OSRM, GraphHopper ya da Google), aramalar için Nominatim'e ve radar sorgusu için bakanlığa
gider. Gemini'ye yalnızca uyarı ve sohbet için gereken bilgi gönderilir. Seyyar radar bildirimlerin ve
anahtarların telefonda kalır.

## Bilinen sınırlamalar

- Bakanlık şu an radar konumlarını vermiyor, sabit radarlar EGM ve OSM verisinden geliyor. Seyyar
  radarlar için herkese açık bir kaynak bulamadık, bu yüzden yalnızca senin bildirimlerin kullanılıyor.
- Uyarılar navigasyon başlatılmışken çalışır. Rota açmadan sürerken uyarı gelmez.
- Bakanlık ve EGM verisinde radarın hangi yönü ölçtüğü yazmıyor, karşı yöndeki radar için de uyarı
  gelebilir.
- Herkese açık OSRM sunucusu yoğun saatlerde yavaşlayabilir. Düzenli kullanıyorsan GraphHopper daha iyi.

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

JDK 17 ve Android SDK 35 gerekiyor. Android Auto'yu bilgisayarda denemek için
[Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu) kullanılabilir.

Yeni sürüm çıkarmak için `app/build.gradle.kts` içindeki `versionCode` ve `versionName` değerlerini
artırıp `v0.x.y` etiketini push'lamak yeterli. GitHub Actions APK'yi derleyip Releases sayfasına koyar.

## Lisanslar

- Harita verisi © OpenStreetMap katkıcıları (ODbL), vektör karolar © OpenFreeMap / OpenMapTiles.
- Harita motoru MapLibre Native (BSD-2).
- Yazı tipleri Space Grotesk ve Inter, SIL Open Font License 1.1 (`third_party/fonts/`).
- APK, güncellemeler üstüne kurulabilsin diye repodaki sabit anahtarla (`app/debug.keystore`)
  imzalanıyor. Play Store'a yüklemek isteyen kendi gizli anahtarını kullanmalı.
