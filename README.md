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
- **Simülasyon modu:** evde uyarıları denemek için araç rotada sanal olarak ilerler.

## Tasarım

"Gece sürüşü yol levhası" teması: koyu lacivert zemin, koyu OSM haritası (CARTO), mavi rota,
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

## Ayarlar

| Ayar | Açıklama |
|------|----------|
| Gemini API anahtarı | Sesli asistan ve laf sokan uyarılar için |
| Konuşma tarzı | Kibar / Şakacı / Sert argo (küfürsüz) / Ağzı bozuk |
| Asistan sesi | Gemini'nin hazır sesleri (Puck, Charon, Kore…) |
| Radar uyarılarını Gemini üretsin | Kapalıysa her zaman düz uyarı okunur |
| Live / uyarı modeli | Varsayılanlar: `gemini-2.5-flash-native-audio-preview-09-2025`, `gemini-flash-latest`. Google model adlarını değiştirirse buradan güncellenir |
| Rota servisi | OSRM / GraphHopper / Google ve anahtarları |
| Harita karo adresi | Android Auto haritası için (varsayılan: OSM) |
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
- Varsayılan koyu harita CARTO'nun ücretsiz basemap'idir (kişisel/ticari olmayan kullanım için).
  Çok kullanılacaksa ayarlardan başka bir karo sağlayıcısı girilebilir.

## Lisanslar

- Yazı tipleri: Space Grotesk ve Inter, SIL Open Font License 1.1 (`third_party/fonts/`).
- Harita verisi © OpenStreetMap katkıcıları; koyu harita stili © CARTO.
- Uygulama, kolay kurulum için repodaki sabit debug anahtarıyla (`app/debug.keystore`) imzalanır. Bu
  sayede güncellemeler üzerine kurulur. Play Store'a yüklemek için kendi gizli imza anahtarınızı
  kullanmalısınız.
