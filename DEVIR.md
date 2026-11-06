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
| Harita | **MapLibre vektör harita** (OpenFreeMap, `assets/map/night.json`), Google Maps tarzı: 3D eğik, yön yukarı, araç yola oturur. Ayarlardan CARTO / özel karo seçilebilir |
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
  geo/                LatLng, haversine, polyline, RouteGeometry (izdüşüm)
  model/              Radar, SpeedCorridor, RouteHazard, NavRoute, RouteStep, ManeuverKind, Place
  ministry/           MinistryApi, DistrictDirectory (+önbellek), HazardFinder
  routing/            OsrmRouting, GraphHopperRouting, GoogleRouting, TurkishInstructions
  place/              PlaceSearch (Nominatim, TR)
  nav/                NavigationTracker: GPS→rota, manevra anonsları, rotadan çıkma, varış
  alert/              AlertEngine: 2 km/500 m, prefetch (3,2 km / 1,3 km), koridor ortalama hız
                      WarningText: Persona (4 seviye), Gemini istemleri, yedek cümleler
  gemini/             GeminiTextClient (REST), LiveProtocol + GeminiLiveSession (WebSocket),
                      GeminiModels (ListModels + otomatik seçim), GeminiTts (doğal ses)
  src/test/           41 birim testi (hepsi geçiyor) + JSON fixture'lar
app/                  Android uygulaması (namespace com.radarnav, applicationId com.radarnav.app)
  RadarNavApp.kt      AppGraph: tek örnekli bağımlılıklar, UiEvent akışı
  data/               Settings (SharedPreferences)
  nav/                NavigationController (beyin), NavigationService (ön plan, konum),
                      LocationSource (fused 500 ms, +simülasyon), HeadingProvider (GPS/rota/pusula),
                      Speaker (telefon TTS yedeği), WarningVoice
  assistant/          AssistantManager (Live oturumu, mikrofon, hoparlör), AssistantTools (function calling),
                      GeminiResolver (anahtarın erişebildiği modeli seçer)
  audio/              PhoneMicInput (16 kHz, AEC), PcmPlayer (24 kHz), VoiceOutput (Gemini TTS + önbellek)
  map/                NavMapController (telefon + Android Auto ortak MapLibre kontrolü), MapStyles
  ui/                 MainActivity, SettingsActivity (+ Tanılama testleri), UiFormat
  car/                RadarCarAppService/RadarSession, CarMapRenderer (VirtualDisplay + Presentation içinde MapView), CarHudView,
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
- Ses (TTS): `gemini-2.5-flash-preview-tts`
- Uyarı metni: `gemini-flash-latest` (`thinkingBudget: 0` ile; 400 dönerse onsuz tekrar dener)
- Ayardaki model anahtarla erişilemiyorsa `GeminiResolver` ListModels ile en uygununu seçer.

Google model adlarını sık değiştiriyor. Live bağlantısı "reddedildi" derse önce
[ai.google.dev/gemini-api/docs/models](https://ai.google.dev/gemini-api/docs/models) üzerinden güncel Live
modelinin adını kontrol et.

### Sürümler

AGP 8.7.3, Kotlin 2.0.21, Gradle 8.14.3 (wrapper), compileSdk/targetSdk 35, minSdk 26,
androidx.car.app 1.4.0, MapLibre 11.8.8, play-services-location 21.3.0, OkHttp 4.12.0, kotlinx-serialization 1.7.3, coroutines 1.9.0.
İmza: `app/debug.keystore` (sabit debug anahtarı, şifre `android`). Release de bununla imzalanıyor.

## 5. Mevcut durum

- ✅ `core` derleniyor, **57 test geçiyor** (`./gradlew :core:test`); Android Auto ekran testleri 5/5
  (`./gradlew :app:testDebugUnitTest`).
- ✅ `app` GitHub Actions'ta derleniyor (son başarılı: commit `cc9b5b2`, run 42256182854). Her push'ta
  `RadarNavigasyon-apk` artifact'ı çıkar; `v*` etiketinde release yapılır.
- ✅ Kullanıcının telefonunda doğrulandı: bakanlık servisi (81 il, ilçeler, radar sorgusu),
  NetworkOnMainThread düzeltmesi, canlı konum takibi.
- ❓ Cihazda **henüz doğrulanmadı**: Gemini Live sohbet (önceden "dinliyor ama cevap yok" sorunu vardı;
  `cc9b5b2` ile realtimeInput.text + otomatik model seçimi eklendi), Gemini TTS sesi, MapLibre görünümü
  ve pusula dönüşü, Android Auto (VirtualDisplay harita).
- 🎨 Arayüz tasarımı Design tuvalinde: https://claude.ai/artifact/BsohkFaf5wVpPaxgQLJ3FP

### 2026-11-06 oturumu: test ve iyileştirmeler

Gerçek servislere karşı canlı testler yazıldı (`core/src/test/.../live/LiveServicesTest.kt`,
`RADARNAV_LIVE=1 GEMINI_API_KEY=… ./gradlew :core:test --tests '*LiveServicesTest*'`). Bulgular ve düzeltmeler:

- **Bakanlık servisi kararsız:** saatlerce `{"success":false,"message":"Servis çağrılırken hata oluştu"}` döndü
  (bakanlığın kendi sitesi de). Yarım çalıştığı anlarda `RadarCount>0` ama `Radars: []` geldi. Çalışınca konumlu
  veri veriyor (site `activity===3 && owner===2` filtreliyor). Düzeltmeler:
  - Bakanlık yanıtları ilçe çifti başına **30 gün kalıcı** saklanır (`HazardFinder(store=…)`); çökünce/yeniden açılınca kullanılır.
  - "Konumsuz" yanıt eski konumları silmez; bakanlığın günlük sayısı plana ve sesli özete eklenir.
  - `HazardFinder.find(budgetMs=12_000)`: rota artık bakanlığı/OSM'yi en fazla 12 sn bekler (önce 406 sn sürüyordu).
    Arka plan yenilemesi 90 sn bekler.
- **OSM sabit kameraları** (`core/osm/OsmCameraSource.kt`, kullanıcı kararı "OSM + bakanlık"):
  `highway=speed_camera`; Türkiye anlık görüntüsü `app/src/main/assets/osm/speed_cameras_tr.json` (ayrı commit; dosya yoksa uygulama Overpass yanıtını bekler; Geofabrik'ten
  `~/tools/osm-snapshot/extract.py` ile), Overpass yalnızca arka planda haftalık güncelleme (tek istekte tüm kareler).
  Ölçüm: tek yönlü yollardaki kameraların %91'inde `direction` = kameranın **baktığı** yön → bizim yönümüze bakan
  kamera karşı şeridi denetler, atılır (fark > 135°). OSM toleransı 50 m; aynı yerde bakanlık kaydı varsa o tutulur.
- **Gemini:** ayarlara yazılmış eski varsayılan model adları (`GeminiModels.LEGACY_DEFAULTS`) artık "otomatik" sayılır;
  sürüm numarasına göre en yeni model seçilir (canlı: Live `gemini-3.8-live`, TTS `gemini-3.8-flash-lite-tts`).
  Live protokolü araç çağrısı dahil JVM'de çalışıyor → telefondaki "cevap yok" sorunu büyük olasılıkla mikrofon/ses yolunda.
- **TTS:** "Stil: metin" biçiminde model stili de sesli okuyordu (3,5 sn'lik cümle 10+ sn). Artık
  `# Style: …\n# Transcript…` biçimi (`GeminiTts.prompt`), İngilizce ton yönergesi.
- **Hız sınırı garantisi:** `WarningText.ensureLimit`: Gemini sınırı söylemeyi unutursa cümleye eklenir.
- **Android Auto:** `RoutePreviewNavigationTemplate` satırında DistanceSpan/DurationSpan yoktu → arabada rota
  önizlemesi **çöküyordu**. Düzeltildi; `app/src/test/.../car/CarScreensTest.kt` (Robolectric + car app-testing) tüm
  ekranları her durumda kurar. Not: test JVM'i Türkçe yerel ayarda `lınux` hatası verir, `build.gradle.kts`'de en_US.
- **Tasarım:** Open Design + Gemini 3.1 Pro ile "Kanka" yönü seçildi (açık/sıcak, konuşma balonlu uyarı, gece modu,
  Android Auto HUD). Dosyalar sunucuda Open Design projesi `radar-nav-redesign`, `kanka/` (henüz koda geçirilmedi).

**İlk yapılacak:** (1) Telefon bağlıyken `adb logcat | grep -i radarnav` ile Gemini Live sohbetini dene (model artık
3.8-live). (2) Kanka tasarımını `app/` arayüzüne geçir. (3) Bakanlık servisi düzelince `CreateRoute` yanıtını kaydedip
`owner` alanını incele.

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

Gereksinimler: JDK 17, Android SDK (Android Studio ile gelir; platform 35 + build-tools),
telefonda USB hata ayıklama (`adb devices` ile görünmeli). Claude Code: `npm install -g @anthropic-ai/claude-code`.

Claude Code'a verilebilecek ilk komut örneği:

> DEVIR.md dosyasını oku. `./gradlew :core:test` ve `./gradlew :app:assembleDebug` çalıştır,
> APK'yı `adb install -r` ile telefona kur. Sonra §5'teki "İlk yapılacak" ile devam et.

Yerelde artık `adb logcat | grep -i radarnav` ile canlı log görülebilir; Gemini/Live sorunlarını
ayıklamak için en hızlı yol bu (`GeminiLiveSession` kapanma kodlarını `onError` ile bildiriyor).

### Android Auto testi

- Telefonda: Android Auto ayarları → "Sürüm"e 10 kez dokun → Geliştirici ayarları → **Bilinmeyen kaynaklar** açık.
- Bilgisayarda: [Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu).
  DHU'daki "otomatik sürüş" komutu `onAutoDriveEnabled` → simülasyonu başlatır.
- Ayarlar → **Sürüş simülasyonu** açılırsa navigasyon başlayınca araç rotada sanal olarak ilerler
  (GPS yoksa başlangıç noktası Ankara Kızılay).

## 7. Kalan işler / bilinen riskler (öncelik sırasıyla)

1. **Gemini Live sohbeti cihazda çalıştır** (bkz. §5). Yankı olursa (asistan kendi sesini duyarsa)
   konuşurken mikrofonu kıs ya da araba hoparlörü/kulaklıkla dene.
2. **Gemini TTS sesi**: gecikme (uyarı 1,5–2 sn bütçe, yoksa telefon TTS), kota/429 sonrası 3 dk bekleme.
3. **Android Auto / DHU**: VirtualDisplay haritası, `NavigationTemplate` pan, `Alert` (Car API 5),
   `CarAudioRecord`, araba hızı (`CAR_SPEED`).
4. Harita: 3D kamera, yola oturma, pusula ile dönüş görsel olarak doğrulanmalı.
5. Radar yön bilgisi: OSM kameraları için çözüldü (`facing`); bakanlık radarlarında `direction` alanı var ama hep null.
5b. Rotasız sürüşte (navigasyon başlatılmadan) radar uyarısı yok; Android Auto'da da HUD yalnızca navigasyonda.
6. Bakanlık sunucusu: tarayıcı taklidi yapma, 403'te tekrar deneme yok (bilinçli karar). Uygulama
   kendini dürüstçe tanıtır; yalnızca geçici hatalar (IO, 429, 5xx) tekrar denenir.
7. Play Store için: gerçek imza anahtarı, `HostValidator` allowlist'i, gizlilik politikası.

## 8. Commit geçmişi

1. `core: radar, rota, uyarı motoru ve Gemini istemcileri`
2. `app: Android navigasyon uygulaması, Android Auto ve Gemini asistanı`
3. `ci: hazır kurulu Android SDK'yı kullan`
4. `docs: devir dosyası` (bu dosya)
5. `uyarılar: anlık hıza göre 4 kademe, 200 m ek uyarı, araba hız göstergesi`
6. `arayüz: Design tuvalindeki gece sürüşü tasarımı uygulandı`
8. `radar servisi, otonom güncelleme, anlık konum, arayüz düzeni` (yalnızca rotaya yakın illerin
   ilçeleri, geçici hatalarda yeniden deneme, 403'te zorlamama, önbellek + 15 dk yenileme,
   fused konum 500 ms, Ayarlar → Tanılama → Radar servisini test et)
10. `ses ve sohbet`: uyarılar ve yol tarifi Gemini TTS ile (kişilik + sertliğe göre ton,
    önbellek, ses hazırlanamazsa telefon sesi); GeminiResolver anahtarın erişebildiği modelleri
    sorup Live/TTS/metin modelini otomatik seçer; Live'da ilk mesaj realtimeInput.text ile;
    Ayarlar → 'Gemini'yi test et' ve 'Sesi dinle'.
9. `il/ilçe listesi bir kez, arka planda ve hız sınırlı indirilir` (prefetchAll: istekler arası
   1,5 sn, sırayla; 180 gün önbellek; yarıda kalırsa sonraki açılışta sürer)
7. `harita: MapLibre vektör harita, Google Maps tarzı sürüş kamerası` (osmdroid ve elle çizilen
   karo haritası kaldırıldı; `map/NavMapController` telefon ve Android Auto ortak; Android Auto'da
   VirtualDisplay + Presentation içinde MapView; stil `assets/map/night.json`, veri OpenFreeMap;
   yön `nav/HeadingProvider`: hızlıyken GPS/rota, dururken pusula)
