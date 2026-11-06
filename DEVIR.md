# Radar Navigasyon: Proje Devir Dosyası

Bu dosya, projeyi yerelde Claude Code ile (ya da elle) devam ettirmek için gereken her şeyi içerir:
istekler, verilen kararlar, mimari, mevcut durum ve kalan işler.

> **Claude Code için not:** Kullanıcıyla her zaman **Türkçe** konuş; teknik terimler (API, commit, build vb.)
> olduğu gibi kalabilir. Kullanıcı soru sorulmasından memnun: belirsiz kararlarda soru sor.

---

## 1. Kullanıcının isteği (özet)

Android için bir navigasyon uygulaması:

1. Güzergah üzerindeki **her hız kamerasını** söylesin.
2. **Android Auto** ile uyumlu olsun.
3. Ayarlardan **Gemini API anahtarı** girilebilsin. Gemini **sesli** cevap versin ve onunla
   **asistan gibi sohbet** edilebilsin.
4. Radara yaklaşırken **laf sokarak / söylenerek / argo kullanarak**, esprili bir şekilde yavaşlaması
   gerektiğini ve **hız sınırını** söylesin.

Veri kaynağı: <https://www.icisleri.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari>

## 2. Kullanıcıyla netleştirilen kararlar

| Konu | Karar |
|------|-------|
| Navigasyon | **Tam navigasyon**: adres arama, sesli dönüş dönüş tarif, yeniden rota |
| Rota servisi | **Hepsi** eklenecek, ayarlardan seçilecek: OSRM (varsayılan, anahtarsız), GraphHopper, Google Directions |
| Android Auto haritası | **Tam OSM harita** (karolar dahil); sade vektör görünüm değil |
| Gemini ses | **Gemini Live API** (gerçek zamanlı, araya girilebilir) |
| Asistan | **Butonla** başlar, **sürüş bilgisini bilir**, **sesle rota değiştirebilir** (function calling). Sürekli dinleme yok |
| Argo dozu | **Ayarlanabilir**: Kibar / Şakacı / Sert argo (küfürsüz) / Ağzı bozuk (hafif küfür, nefret söylemi yok) |
| Uyarı metni | **Hep Gemini üretsin** |
| Gemini yoksa / geç kalırsa | **Düz yedek uyarı**: "Radar 500 metre ileride. Hız sınırı 82." (2,5 sn zaman aşımı) |
| Uyarı mesafeleri | **2 km + 500 m**, 500 m'den sonra hâlâ hızlıysa **200 m'de ek, en sert uyarı** |
| Uyarı sertliği | **Anlık hıza göre 4 kademe** (`Severity`): sınırın altı = sakin · 0-5 km/s üstü = hafif takılma · 6-20 üstü = laf sokma · 20+ üstü = bağırarak en sert. Seçili kişilik (argo seviyesi) tavandır. Önceden hazırlanan Gemini metni, uyarı anında kademe değiştiyse yeniden üretilir. Yedek cümleler de kademeye göre sertleşir |
| Hız kaynağı | Android Auto'ya bağlıyken **arabanın gösterge hızı** (Car API 3+, `CAR_SPEED` izni), değilse GPS |
| Görünüm | Design tuvalindeki "gece sürüşü yol levhası" teması uygulamaya geçirildi: koyu tema (`colors.xml`), Space Grotesk/Inter (`res/font`, OFL), koyu harita: standart OSM karoları `MapStyle.nightFilter` ile cihazda koyulaştırılır (CARTO anahtar istediği için bırakıldı; eski kayıtlı CARTO adresi otomatik değiştirilir), levha tarzı radar diski, kehribar koridor rozeti, 4 çubuklu sertlik göstergeli uyarı kartı, Gemini Live paneli (balonlar, öneri çipleri, sessize alma), son hedefler. Android Auto'da harita çizimi aynı temada + alt uyarı kartı |
| Haritada gösterim | Tüm radarlar rota üzerinde hız sınırı diskiyle; hız koridorları rota üzerinde turuncu bölüm + başlangıç (sınır) ve bitiş ("SON") işaretleri; telefonda ve Android Auto'da |
| Rota başında | Tüm radarlar **listede/haritada gösterilsin**, sesli okunmasın; yaklaşınca tek tek uyarsın |
| Derleme | Burada derleme + **GitHub Actions** ile APK |

## 3. Bakanlık API'si (tersine mühendislikle bulundu, kimlik doğrulama yok)

Taban: `https://www.icisleri.gov.tr/ISAYWebPart/PolGenControlPointV2`

- `GET /GetCities` → `[{"Id":1,"Name":"Adana"}, …]`. Id plaka kodu **değil**.
- `GET /GetDistricts?cityId={id}` → `[{"Id","Name","Latitude","Longitude"}]`
- `POST /CreateRoute` (form-urlencoded): `fromLatitude, fromLongitude, toLatitude, toLongitude, fromDistrictId, toDistrictId`
  → `{success, data: {Radars[], SpeedTunnels[], Coordinates[], RadarCount, ControlPointCount, CorridorCount, Cities[]}}`
  - Radar: `id, name, provinceName, districtName, activity, owner, speedLimit, x(=boylam), y(=enlem)`
  - SpeedTunnel: `…, speedLimit, length(km), startLonX, startLatY, endLonX, endLatY, coordinates[{x,y}]`
  - **`activity == 3` aktif** demek (diğer açık kaynak projeler de böyle filtreliyor).
  - `speedLimit` 0 ya da null olabilir → bilinmiyor.
- Gönderilen başlıklar: `User-Agent`, `Referer` (sayfa adresi), `Origin`, `X-Requested-With: XMLHttpRequest`.

Örnek açık kaynak projeler (incelendi): `tialkan/Radar` (iOS, MIT, `API_DOKUMANTASYON.md` var),
`TA4AB/radar-haritasi-tr` (Android, MIT), `hmesutozsoy/radarkontrolturkiye` (PWA, lisanssız: kodu kopyalama).

**Önemli tasarım:** API yalnızca ilçeden ilçeye ve kendi güzergahıyla çalışıyor. Bu yüzden
`HazardFinder`, bizim rotamızı ~70 km'lik parçalara böler. Her parçanın uçlarına en yakın ilçeyi bulur,
parça başına `CreateRoute` çağırır. Gelen radarları bizim rotamıza izdüşürür ve yalnızca 90 m içindekileri
tutar (koridorlarda 300 m). İlçe listesi (81 il, ~970 ilçe) bir kez indirilip 30 gün önbellekte tutulur.

## 4. Mimari

```
settings.gradle.kts   :app yalnızca Android SDK varsa dahil edilir (ANDROID_HOME / local.properties)
core/                 Saf Kotlin/JVM, Android'siz derlenir ve test edilir
  geo/                LatLng, haversine, polyline, RouteGeometry (izdüşüm), WebMercator (karo)
  model/              Radar, SpeedCorridor, RouteHazard, NavRoute, RouteStep, ManeuverKind, Place
  ministry/           MinistryApi, DistrictDirectory (+önbellek), HazardFinder
  routing/            OsrmRouting, GraphHopperRouting, GoogleRouting, TurkishInstructions
  place/              PlaceSearch (Nominatim, TR)
  nav/                NavigationTracker: GPS→rota, manevra anonsları, rotadan çıkma, varış
  alert/              AlertEngine: 2 km/500 m, prefetch (3,2 km / 1,3 km), koridor ortalama hız
                      WarningText: Persona (4 seviye), Gemini istemleri, yedek cümleler
  gemini/             GeminiTextClient (REST), LiveProtocol + GeminiLiveSession (WebSocket)
  src/test/           29 birim testi (hepsi geçiyor) + JSON fixture'lar
app/                  Android uygulaması (namespace com.radarnav, applicationId com.radarnav.app)
  RadarNavApp.kt      AppGraph: tek örnekli bağımlılıklar, UiEvent akışı
  data/               Settings (SharedPreferences), TileCache (Android Auto karoları)
  nav/                NavigationController (beyin), NavigationService (ön plan, konum),
                      LocationSource (+simülasyon), Speaker (TTS tr-TR, ducking), WarningVoice
  assistant/          AssistantManager (Live oturumu, mikrofon, hoparlör), AssistantTools (function calling)
  audio/              PhoneMicInput (16 kHz, AEC), PcmPlayer (24 kHz)
  ui/                 MainActivity (osmdroid), SettingsActivity, UiFormat
  car/                RadarCarAppService/RadarSession, CarMapRenderer (Surface'e OSM çizimi),
                      CarScreens (Nav, Search, RoutePreview, HazardList), CarFormat, CarMicInput
```

### Akış

1. Arama (Nominatim) → `NavigationController.plan(place)` → rota servisi → `HazardFinder.find(route)` → `NavState.Planned`
2. "Başlat" → `NavigationService` (ön plan) + GPS → her konumda `NavigationTracker.update` + `AlertEngine.update`
3. `AlertEvent.Prefetch` → Gemini metni önceden üretilir. `Approach` → hazırsa okunur, değilse 2,5 sn bekler, sonra yedek.
4. Rotadan çıkınca yeniden rota alınır. Bilinen radarlar hemen yeni rotaya yerleştirilir, sonra bakanlığa yeniden sorulur.
5. Asistan: `AssistantManager.toggle { mikrofon }` → Live WebSocket → araçlar:
   `get_drive_status`, `get_upcoming_hazards`, `navigate_to(destination)`, `stop_navigation`.

### Varsayılan modeller (ayarlardan değiştirilebilir)

- Live: `gemini-2.5-flash-native-audio-preview-09-2025`
- Uyarı metni: `gemini-flash-latest` (`thinkingBudget: 0` ile; 400 dönerse onsuz tekrar dener)

Google model adlarını sık değiştiriyor. Live bağlantısı "reddedildi" derse önce
[ai.google.dev/gemini-api/docs/models](https://ai.google.dev/gemini-api/docs/models) üzerinden güncel Live
modelinin adını kontrol et.

### Sürümler

AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 (wrapper), compileSdk/targetSdk 35, minSdk 26,
androidx.car.app 1.4.0, osmdroid 6.1.20, OkHttp 4.12.0, kotlinx-serialization 1.7.3, coroutines 1.9.0.
İmza: `app/debug.keystore` (sabit debug anahtarı, şifre `android`). Release de bununla imzalanıyor.

## 5. Mevcut durum

- ✅ `core` derleniyor, **32/32 test geçiyor** (`./gradlew :core:test`).
- ✅ `app` modülü GitHub Actions'ta derlendi (commit `1b7901a`), APK üretildi. Sonraki değişikliklerin
  (hız kademeleri, araba hızı) CI sonucunu Actions sekmesinden kontrol et.
- 🎨 Arayüz tasarımı (7 ekran) Design tuvalinde: https://claude.ai/artifact/BsohkFaf5wVpPaxgQLJ3FP
- ❌ Gerçek cihazda, Android Auto'da (DHU) ve gerçek bakanlık/Gemini sunucularıyla **test edilmedi**.

## 6. Yerelde devam etmek

```bash
git clone https://github.com/emocantemocan/radar.git
cd radar
git checkout ccr-452127ca-l4ucey
# Android Studio ile aç (SDK yolunu local.properties'e kendisi yazar) ya da:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :core:test
./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Claude Code'a verilebilecek ilk komut örneği:

> DEVIR.md dosyasını oku. `./gradlew :app:assembleDebug` çalıştır, çıkan derleme hatalarını düzelt,
> sonra `:core:test` ile testlerin hâlâ geçtiğini doğrula ve commit'le.

### Android Auto testi

- Telefonda: Android Auto ayarları → "Sürüm"e 10 kez dokun → Geliştirici ayarları → **Bilinmeyen kaynaklar** açık.
- Bilgisayarda: [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu).
  DHU'daki "otomatik sürüş" komutu `onAutoDriveEnabled` → simülasyonu başlatır.
- Ayarlar → **Sürüş simülasyonu** açılırsa navigasyon başlayınca araç rotada sanal olarak ilerler
  (GPS yoksa başlangıç noktası Ankara Kızılay).

## 7. Kalan işler / bilinen riskler (öncelik sırasıyla)

1. **Cihazda ve DHU'da çalıştır.** Çalışma zamanında doğrulanması gereken Car App Library API'leri:
   `NavigationTemplate.setPanModeListener`, `Action.PAN`, aynı aksiyonda hem ikon hem başlık kullanımı
   (host kısıtları), `RoutePreviewNavigationTemplate.setTitle`, `Alert` (Car API 5),
   `CarAudioRecord` (Car API 5).
2. **Gerçek bakanlık API'siyle dene.** Yanıt biçimi, alan adlarının büyük/küçük harfi (`anyCase`
   ile tolere ediliyor) ve gerekli başlıklar doğrulanmalı.
3. **Gemini Live'ı dene:** kurulum mesajı (`setup`), `realtimeInput.audio`, transkriptler, function calling.
   Telefonda hoparlörden yankı olursa (asistanın kendi sesini duyması) kulaklık/araba hoparlörü ile dene
   ya da konuşurken mikrofonu kıs.
4. Android Auto haritasında karo yüklenme/performans. Gerekirse eksik karolar için üst zoom karosunu kullan
   (`TileCache.peek` hazır).
5. Radar yön bilgisi yok: karşı şeritteki radarlar için de uyarı gelebilir.
6. İsteğe bağlı: başlangıç ekranında son aranan yerler, favoriler; uyarı öncesi kısa bip sesi;
   Gemini TTS sesiyle uyarı okuma (şu an uyarılar Android TTS ile okunuyor, sohbet Gemini sesiyle).
7. Play Store'a çıkılacaksa: gerçek imza anahtarı, `HostValidator` allowlist'i, gizlilik politikası,
   Android Auto navigasyon uygulaması incelemesi.

## 8. Commit geçmişi

1. `core: radar, rota, uyarı motoru ve Gemini istemcileri`
2. `app: Android navigasyon uygulaması, Android Auto ve Gemini asistanı`
3. `ci: hazır kurulu Android SDK'yı kullan`
4. `docs: devir dosyası` (bu dosya)
5. `uyarılar: anlık hıza göre 4 kademe, 200 m ek uyarı, araba hız göstergesi`
6. `arayüz: Design tuvalindeki gece sürüşü tasarımı uygulandı`
