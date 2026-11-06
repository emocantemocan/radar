# Radar Navigasyon

Türkiye için açık kaynak Android navigasyon uygulaması. Güzergahtaki **tüm radar ve ortalama hız koridorlarını**
bilir, yaklaşırken **laf sokarak** yavaşlamanı ve hız sınırını söyler. **Android Auto** ile uyumludur ve
**Gemini** ile sesli sohbet edebileceğin bir asistan içerir.

Radar verisi: [T.C. İçişleri Bakanlığı – İller Arası Radar ve Kontrol Noktası Uygulama Sayıları](https://www.icisleri.gov.tr/iller-arasi-radar-ve-kontrol-noktasi-uygulama-sayilari)

> Uyarılar bilgilendirme amaçlıdır. Bakanlık verisi her radarı kapsamayabilir; trafik kurallarına uymak
> sürücünün sorumluluğundadır. Uygulama radardan kaçmayı değil, yavaşlamayı hedefler.

## Özellikler

- **Tam navigasyon:** adres arama (OpenStreetMap Nominatim), sesli Türkçe dönüş dönüş yol tarifi, rotadan
  çıkınca yeniden rota hesaplama.
- **Rota servisi ayarlardan seçilir:** OSRM (ücretsiz, anahtarsız), GraphHopper (ücretsiz API anahtarı) veya
  Google Directions (API anahtarı). Seçili servis hata verirse otomatik olarak OSRM'e geçer.
- **Güzergahtaki tüm radarlar:** rota başlarken tüm radar ve koridorlar haritada ve listede görünür.
  Bakanlık API'si yalnızca ilçeden ilçeye çalıştığı için rota ~70 km'lik parçalara bölünür, her parça ayrı
  sorgulanır ve yalnızca gerçekten senin rotanın üzerindeki noktalar tutulur.
- **Uyarılar:** her radar için **2 km** ve **500 m** kala. Hız koridorunda giriş, ortalama hız takibi
  (sınırı aşarsan dakikada bir uyarı) ve çıkış.
- **Gemini laf sokar:** uyarı cümlelerini Gemini seçtiğin tarzda üretir: *Kibar, Şakacı, Sert argo
  (küfürsüz), Ağzı bozuk*. Hızın sınırın üzerindeyse daha sert konuşur. Gecikmeyi gizlemek için cümle
  radara ~1 km kala hazırlanmaya başlar. Gemini 2,5 saniyede cevap vermezse ya da internet yoksa düz bir
  yedek uyarı okunur: "Radar 500 metre ileride. Hız sınırı 82."
- **Gemini Live sesli asistan:** mikrofon düğmesiyle başlar. Gerçek zamanlı konuşursun, araya girebilirsin.
  Asistan sürüş durumunu bilir (hız, kalan yol, varış saati, sıradaki radar ve sınırı). Sesle rota da
  değiştirebilir: "Beni Bolu'ya götür".
- **Android Auto:** OSM haritası araba ekranında, manevra kartı, kalan süre, radar listesi, araç
  ekranında radar uyarısı, araba mikrofonuyla asistan.
- **Google Maps gibi sürüş kamerası:** 3D eğik, gidilen yön hep yukarıda, ok yola yapışık ve kayarak
  ilerler; hız arttıkça harita uzaklaşır. Dururken yön telefonun pusulasından alınır. Telefonda ve
  Android Auto'da aynı vektör harita.
- **Simülasyon modu:** evde uyarıları denemek için araç rotada sanal olarak ilerler.

## Tasarım

"Gece sürüşü yol levhası" teması: koyu lacivert zemin, MapLibre vektör haritası (veri: OpenFreeMap, anahtarsız; lacivert gece stili uygulamaya gömülü), mavi rota,
Türk hız sınırı levhası tarzında radar işaretleri, kehribar hız koridorları, Space Grotesk ve Inter
yazı tipleri. Uyarı kartı anlık hıza göre 4 kademede renk ve dolu çubuk sayısı değiştirir.
Tasarım tuvali: https://claude.ai/artifact/BsohkFaf5wVpPaxgQLJ3FP

## Kurulum

1. [Actions](../../actions) sekmesinden son başarılı derlemeyi açıp **RadarNavigasyon-apk** dosyasını indir.
   `v` ile başlayan bir etiket push'lanırsa APK [Releases](../../releases) sayfasına da konur.
2. Zip'ten çıkan `RadarNavigasyon.apk` dosyasını telefona kur ("Bilinmeyen kaynaklar" izni gerekir).
3. Uygulamayı aç, konum ve bildirim izni ver.
4. **Ayarlar → Gemini API anahtarı** alanına anahtarını gir. Anahtarı ücretsiz olarak
   [aistudio.google.com/apikey](https://aistudio.google.com/apikey) adresinden alabilirsin.

### Android Auto'da görünmesi için

Play Store dışından kurulan uygulamalar Android Auto'da varsayılan olarak gizlidir:

1. Telefonda **Android Auto ayarları**'nı aç, en alttaki **Sürüm** satırına 10 kez dokun.
   Geliştirici modu açılır.
2. Sağ üstteki menüden **Geliştirici ayarları** → **Bilinmeyen kaynaklar**'ı aç.
3. Telefonu arabaya bağla. "Radar Navigasyon" uygulama listesinde görünür.

## API anahtarları nasıl alınır?

Ayarlar ekranında her anahtarın altında "nasıl alınır?" bağlantısı var. Anahtar kutusundaki göz
ikonuyla anahtarı gösterip gizleyebilirsin; listede yalnızca son 4 karakteri görünür.

### Gemini (asistan ve laf sokan uyarılar), ücretsiz
1. <https://aistudio.google.com/apikey> adresine Google hesabınla gir.
2. **Create API key** → bir proje seç (yoksa otomatik oluşturulur) → anahtarı kopyala.
3. Uygulamada Ayarlar → **Gemini API anahtarı** alanına yapıştır.

### Google Maps (Google Directions ile rota), ücretli olabilir
1. <https://console.cloud.google.com/> adresine gir, üstten **yeni proje** oluştur (ör. "radar-nav").
2. **Faturalandırma**'yı etkinleştir. Google kart bilgisi ister; aylık ücretsiz kullanım kotası
   kişisel kullanım için genelde yeterlidir ama aşarsan ücret çıkar. İstersen **Bütçe ve uyarılar**
   bölümünden aylık bütçe uyarısı (ör. 1 $) kur.
3. **API'ler ve Hizmetler → Kitaplık** bölümünde **Directions API**'yi bulup **Etkinleştir**'e bas.
4. **API'ler ve Hizmetler → Kimlik bilgileri → Kimlik bilgisi oluştur → API anahtarı**.
5. Güvenlik için anahtarı kısıtla: **API kısıtlamaları → Anahtarı kısıtla → Directions API**.
   (Uygulama anahtarı doğrudan REST ile kullandığı için "Android uygulamaları" kısıtlamasını seçme;
   o kısıtlama yalnızca Google'ın Android SDK'larıyla çalışır.)
6. Uygulamada Ayarlar → **Google Maps API anahtarı**'na yapıştır ve **Rota servisi → Google Directions** seç.

### GraphHopper (rota), ücretsiz
1. <https://graphhopper.com/dashboard/signup> adresinden ücretsiz hesap aç.
2. Dashboard → **API Keys → Add API Key** → anahtarı kopyala (günde 500 rota ücretsiz).
3. Ayarlar → **GraphHopper API anahtarı** ve **Rota servisi → GraphHopper**.

### CARTO (isteğe bağlı koyu harita)
Varsayılan "OSM koyu" harita anahtarsız çalışır. CARTO'nun koyu haritasını istersen:
1. <https://app.carto.com/> adresinden hesap aç, **Developers / Credentials** bölümünden bir
   API erişim anahtarı (token) oluştur.
2. Ayarlar → **Harita → CARTO koyu** seç ve **CARTO API anahtarı**'na yapıştır.
3. Anahtar karo adresindeki `{key}` yerine konur (varsayılan: `...dark_all/{z}/{x}/{y}.png?api_key={key}`).
   CARTO anahtarı farklı bir parametre adıyla istiyorsa **CARTO karo adresi** alanından düzeltebilirsin.

## Ayarlar

| Ayar | Açıklama |
|------|----------|
| Gemini API anahtarı | Sesli asistan ve laf sokan uyarılar için |
| Konuşma tarzı | Kibar / Şakacı / Sert argo (küfürsüz) / Ağzı bozuk |
| Asistan sesi | Gemini'nin hazır sesleri (Puck, Charon, Kore…) |
| Radar uyarılarını Gemini üretsin | Kapalıysa her zaman düz uyarı okunur |
| Live / uyarı modeli | Varsayılanlar: `gemini-2.5-flash-native-audio-preview-09-2025`, `gemini-flash-latest`. Google model adlarını değiştirirse buradan güncellenir |
| Rota servisi | OSRM / GraphHopper / Google ve anahtarları |
| Harita | Gece vektör haritası (anahtarsız, varsayılan) / özel vektör stil adresi / CARTO koyu (anahtar) / özel karo adresi |
| Sürüş simülasyonu | Navigasyon başlayınca sanal sürüş (hız ayarlanabilir) |

## Geliştirme

```
core/   Android'den bağımsız iş mantığı (Kotlin/JVM, birim testli)
        ministry/  Bakanlık API istemcisi, ilçe dizini, rotadaki radarları bulma
        routing/   OSRM, GraphHopper, Google + Türkçe yönlendirme metinleri
        nav/       GPS'i rotaya oturtma, manevra anonsları, rotadan çıkma
        alert/     2 km / 500 m uyarı motoru, koridor ortalama hız, Gemini istemleri
        gemini/    Gemini metin API'si ve Gemini Live (WebSocket) protokolü
app/    Android uygulaması
        ui/        Telefon arayüzü (osmdroid haritası, ayarlar)
        car/       Android Auto (NavigationTemplate, harita çizimi, ekranlar)
        nav/       Navigasyon denetleyicisi, ön plan servisi, TTS, uyarı seslendirme
        assistant/ Gemini Live asistanı ve araçları (function calling)
```

```bash
./gradlew :core:test            # Android SDK gerekmez
./gradlew :app:assembleDebug    # Android SDK gerekir (ANDROID_HOME)
```

Android Auto'yu bilgisayarda denemek için
[Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu) kullanılabilir. DHU'nun
"otomatik sürüş" komutu simülasyonu başlatır.

## Bilinen sınırlamalar

- Bakanlık verisinde radarın hangi yönü ölçtüğü yok, bu yüzden karşı yöndeki radarlar için de uyarı
  gelebilir.
- Herkese açık OSRM sunucusu yoğunlukta yavaşlayabilir; düzenli kullanımda GraphHopper anahtarı önerilir.
- Harita verisi OpenFreeMap'in ücretsiz sunucusundan gelir. İstenirse ayarlardan başka bir vektör stil
  adresi (ör. MapTiler) ya da resim karo sağlayıcısı girilebilir.

## Lisanslar

- Yazı tipleri: Space Grotesk ve Inter, SIL Open Font License 1.1 (`third_party/fonts/`).
- Harita verisi © OpenStreetMap katkıcıları, vektör karolar © OpenFreeMap / OpenMapTiles.
- Harita motoru: MapLibre Native (BSD-2).
- Uygulama, kolay kurulum için repodaki sabit debug anahtarıyla (`app/debug.keystore`) imzalanır. Bu
  sayede güncellemeler üzerine kurulur. Play Store'a yüklemek için kendi gizli imza anahtarınızı
  kullanmalısınız.
