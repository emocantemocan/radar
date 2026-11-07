# Radar Navigasyon

Türkiye için açık kaynak Android navigasyon uygulaması. Güzergahındaki radarları, EDS noktalarını ve
ortalama hız koridorlarını bilir; yaklaşırken hız sınırını söyler, hızlıysan **laf sokarak** yavaşlatır.
**Android Auto** ile çalışır ve **Gemini** ile sesli sohbet edebileceğin bir asistanı vardır.

**[⬇ Son sürümü indir (APK)](../../releases/latest)**

> Uyarılar bilgilendirme amaçlıdır. Hiçbir kaynak her radarı kapsamaz; trafik kurallarına uymak
> sürücünün sorumluluğundadır. Uygulama radardan kaçmayı değil, yavaşlamayı hedefler.

## Neler yapar?

### Navigasyon
- Adres ve yer arama (OpenStreetMap Nominatim), sesli Türkçe dönüş dönüş yol tarifi.
- Rotadan çıkınca yeni rota hesaplar; bilinen radarlar yeni rotaya hemen taşınır.
- Rota servisi ayarlardan seçilir: **OSRM** (ücretsiz, anahtarsız, varsayılan), **GraphHopper**
  (ücretsiz anahtar) ya da **Google Routes API** (anahtar, ücretli olabilir). Seçilen servis hata
  verirse uygulama kendiliğinden OSRM'e geçer ve nedenini kısaca yazar.
- 3D sürüş kamerası: gidilen yön hep yukarıda, ok yola oturur, hız arttıkça harita uzaklaşır.
  Dururken yön telefonun pusulasından alınır.

### Radarlar nereden geliyor?
Rota planlanınca tüm kaynaklar birlikte sorgulanır ve yalnızca **gerçekten senin rotanın üzerindeki**
noktalar tutulur. Aynı yerde birden fazla kaynakta kayıt varsa en güvenilir olan kalır.

| Kaynak | Ne verir | Not |
|---|---|---|
| [İçişleri Bakanlığı](https://www.icisleri.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari) | Ortalama hız koridorları (giriş, çıkış, sınır), günlük radar sayısı | Bakanlık şu an radar **konumlarını** paylaşmıyor, yalnızca sayısını. Konum gelirse uygulama kullanır. |
| [EGM EDS haritası](https://onlineislemler.egm.gov.tr/trafik/sayfalar/edsharita.aspx) | ~1.460 sabit EDS noktası (hız, kırmızı ışık, kavşak) | Uygulamaya gömülü, haftada bir arka planda güncellenir. |
| OpenStreetMap | ~790 sabit hız kamerası, çoğunda yön ve sınır | Uygulamaya gömülü. Karşı şeridi denetleyen kameralar atılır. |
| **Senin bildirimlerin** | Gördüğün seyyar radarlar | Yalnızca telefonunda saklanır, hiçbir sunucuya gitmez. |

Bakanlık yanıtları 30 gün telefonda saklanır; servis çökse bile son bilinen veri kullanılır.

### Uyarılar
- Her radar için **2 km** ve **500 m** kala sesli uyarı ve ekranda kart; kart rengi hızına göre değişir.
- **Ortalama hız koridoru:** girişte haber verir, ortalamanı canlı hesaplar. Ortalama sınırı geçerse
  uyarır ve **kalan yol için hedef hız** söyler ("kalan yolu en fazla 95 ile git"). Ortalaman sınırın
  altına inince "tamam, böyle devam" der. (Koridor takibi navigasyon açıkken çalışır.)
- **Seyyar radar:** radar düğmesine dokun (Android Auto'da "Radar var", ya da asistana "burada radar
  var" de). Bir dahaki geçişte "geçen sefer burada seyyar radar görmüştün" diye uyarır. Basılı tutunca
  o noktadaki kayıt silinir; 180 günden eski kayıtlar kendiliğinden unutulur.
- **Gemini laf sokar:** uyarı cümlelerini Gemini seçtiğin tarzda üretir: *Kibar, Şakacı, Sert argo
  (küfürsüz), Ağzı bozuk*. Hızlıysan daha sert konuşur, hız sınırını her zaman söyler. Gemini
  2,5 saniyede cevap vermezse ya da internet yoksa düz bir yedek uyarı okunur.

### Sesli asistan (Gemini Live)
Mikrofon düğmesiyle başlar; gerçek zamanlı konuşursun, sözünü kesebilirsin. Hızını, kalan yolu, varış
saatini, sıradaki radarı ve koridor ortalamanı bilir. Sesle rota değiştirebilir ("beni Bolu'ya götür"),
navigasyonu bitirebilir, seyyar radar kaydedebilir.

### Android Auto
Araba ekranında harita, manevra kartı, hız ve sınır göstergesi, koridor ortalaması, radar listesi,
radar uyarıları, "Radar var" düğmesi ve araba mikrofonuyla asistan.

## Kurulum

1. **[Releases](../../releases/latest)** sayfasından `RadarNavigasyon.apk` dosyasını telefona indir.
2. Dosyayı açıp kur ("Bilinmeyen uygulamaları yükle" izni istenir). Yeni sürümler eskisinin üstüne kurulur.
3. Uygulamayı aç, konum, bildirim ve (asistan için) mikrofon izni ver.
4. **Ayarlar → Gemini API anahtarı** alanına anahtarını gir (ücretsiz, aşağıda nasıl alınacağı var).
   Anahtar olmadan da navigasyon ve radar uyarıları çalışır; yalnızca asistan ve laf sokan uyarılar olmaz.

Her commit'in derlemesi [Actions](../../actions) sekmesinde de var (GitHub hesabıyla giriş gerekir).

### Android Auto'da görünmesi için
Play Store dışından kurulan uygulamalar Android Auto'da varsayılan olarak gizlidir:
1. Telefonda **Android Auto ayarları**'nı aç, en alttaki **Sürüm** satırına 10 kez dokun.
2. Sağ üstteki menüden **Geliştirici ayarları → Bilinmeyen kaynaklar**'ı aç.
3. Telefonu arabaya bağla; "Radar Navigasyon" uygulama listesinde görünür.

## API anahtarları

Ayarlar ekranında her anahtarın altında "nasıl alınır?" bağlantısı var. Anahtarlar yalnızca telefonda
saklanır; listede son 4 karakteri görünür.

### Gemini (asistan ve laf sokan uyarılar), ücretsiz
1. <https://aistudio.google.com/apikey> adresine Google hesabınla gir.
2. **Create API key** → anahtarı kopyala.
3. Ayarlar → **Gemini API anahtarı**'na yapıştır. Model seçmene gerek yok; uygulama anahtarının
   erişebildiği en yeni modeli kendisi seçer.

### Google Routes API (Google ile rota), ücretli olabilir
1. <https://console.cloud.google.com/> adresinde bir proje oluştur ve **Faturalandırma**'yı aç. Aylık
   ücretsiz kota kişisel kullanıma genelde yeter; **Bütçe ve uyarılar**'dan küçük bir bütçe uyarısı kur.
2. **API'ler ve Hizmetler → Kitaplık**'ta **Routes API**'yi bulup **Etkinleştir**'e bas.
   (Eski "Directions API" yeni projelerde açılmıyor; uygulama Routes API kullanır.)
3. **Kimlik bilgileri → Kimlik bilgisi oluştur → API anahtarı**.
4. Anahtarı kısıtla: **API kısıtlamaları → Routes API**. "Android uygulamaları" kısıtlamasını seçme;
   uygulama anahtarı doğrudan REST ile kullanır.
5. Ayarlar → **Google Maps API anahtarı**'na yapıştır, **Rota servisi → Google** seç.

Routes API açık değilse rota ekranında "Google anahtarında Routes API açık değil" yazar ve OSRM kullanılır.

### GraphHopper (rota), ücretsiz
1. <https://graphhopper.com/dashboard/signup> adresinden hesap aç.
2. **API Keys → Add API Key** (günde 500 rota ücretsiz).
3. Ayarlar → **GraphHopper API anahtarı** ve **Rota servisi → GraphHopper**.

## Ayarlar

| Ayar | Açıklama |
|---|---|
| Gemini API anahtarı | Sesli asistan ve laf sokan uyarılar |
| Konuşma tarzı | Kibar / Şakacı / Sert argo (küfürsüz) / Ağzı bozuk |
| Asistan sesi, uyarı sesi | Gemini'nin doğal sesleri ya da telefonun kendi sesi |
| Radar uyarılarını Gemini üretsin | Kapalıysa her zaman düz uyarı okunur |
| Modeller | Boş bırakılırsa en yeni model otomatik seçilir |
| Rota servisi | OSRM / GraphHopper / Google ve anahtarları |
| Harita | Gece vektör haritası (anahtarsız, varsayılan) ya da özel stil / karo adresi |
| Bildirdiğim seyyar radarları sil | Telefondaki tüm seyyar radar kayıtlarını siler |
| Sürüş simülasyonu | Evde denemek için araç rotada sanal olarak ilerler |

## Gizlilik

- Uygulamanın kendine ait bir sunucusu yok; hesap, kayıt ya da takip yok.
- Konumun yalnızca rota ve arama için seçtiğin servislere (OSRM / GraphHopper / Google, Nominatim) ve
  radar sorgusu için bakanlığa gider. Gemini'ye yalnızca uyarı ve sohbet için gereken bilgiler gider.
- Seyyar radar bildirimlerin ve anahtarların yalnızca telefonda durur.

## Bilinen sınırlamalar

- Bakanlık şu an radar konumlarını vermiyor; sabit radarlar EGM ve OSM verisinden gelir. Seyyar
  radarlar için herkese açık bir kaynak yok, bu yüzden yalnızca kendi bildirimlerin kullanılır.
- Uyarılar navigasyon başlatılmışken çalışır; rotasız sürüşte uyarı yok.
- Bakanlık ve EGM verisinde radarın hangi yönü ölçtüğü yok; karşı yöndeki radar için de uyarı gelebilir.
- Herkese açık OSRM sunucusu yoğunlukta yavaşlayabilir; düzenli kullanımda GraphHopper önerilir.

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

Gereksinimler: JDK 17, Android SDK 35. Android Auto'yu bilgisayarda denemek için
[Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu) kullanılabilir.

Yeni sürüm yayımlamak: `app/build.gradle.kts`'de `versionCode`/`versionName`'i artır, `v0.x.y`
etiketini push'la. GitHub Actions APK'yi derleyip Releases sayfasına koyar.

## Lisanslar

- Harita verisi © OpenStreetMap katkıcıları (ODbL), vektör karolar © OpenFreeMap / OpenMapTiles.
- Harita motoru: MapLibre Native (BSD-2).
- Yazı tipleri: Space Grotesk ve Inter, SIL Open Font License 1.1 (`third_party/fonts/`).
- APK, güncellemelerin üstüne kurulabilmesi için repodaki sabit anahtarla (`app/debug.keystore`)
  imzalanır. Play Store'a yüklemek için kendi gizli anahtarınızı kullanın.
