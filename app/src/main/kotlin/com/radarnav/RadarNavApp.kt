package com.radarnav

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.radarnav.assistant.AssistantManager
import com.radarnav.core.ministry.DistrictCache
import com.radarnav.core.ministry.DistrictDirectory
import com.radarnav.core.ministry.HazardFinder
import com.radarnav.core.ministry.MinistryApi
import com.radarnav.core.place.PlaceSearch
import com.radarnav.data.Settings
import com.radarnav.nav.LocationSource
import com.radarnav.nav.NavigationController
import com.radarnav.nav.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class RadarNavApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)

        // Vektör harita motoru (MapLibre); OpenFreeMap anahtar istemez
        // (Robolectric testlerinde yerel kütüphane yüklenemez; harita orada kullanılmıyor)
        runCatching { org.maplibre.android.MapLibre.getInstance(this) }

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(NAV_CHANNEL, getString(R.string.nav_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val NAV_CHANNEL = "navigation"
    }
}

val Context.graph: AppGraph get() = (applicationContext as RadarNavApp).graph

/** Kullanıcıya gösterilecek kısa olaylar (telefonda Toast, arabada Alert). */
sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class RadarAlert(
        val title: String,
        val text: String,
        val severity: com.radarnav.core.alert.Severity = com.radarnav.core.alert.Severity.CALM,
        /** Uyarının ait olduğu radar/koridor (uyarı kartında metni eşleştirmek için). */
        val hazardId: String? = null,
    ) : UiEvent
}

/** Basit bağımlılık kabı: tüm uygulama tek bir örnek kullanır. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val withUa = if (req.header("User-Agent") == null) {
                req.newBuilder().header("User-Agent", USER_AGENT).build()
            } else req
            chain.proceed(withUa)
        }
        .build()

    val settings = Settings(app)
    val ministry = MinistryApi(http)
    val districts = DistrictDirectory(ministry, FileDistrictCache(File(app.filesDir, "districts.json")))
    /** Sabit hız kameraları (OpenStreetMap); haftada bir, bölge bölge indirilip saklanır. */
    val osmCameras = com.radarnav.core.osm.OsmCameraSource(
        http, FileTextStore(File(app.filesDir, "osm")),
        // Türkiye'deki tüm sabit kameralar uygulamaya gömülü: internet ya da Overpass olmasa da çalışır
        snapshot = {
            runCatching {
                app.assets.open(OSM_SNAPSHOT_ASSET).bufferedReader().use { r ->
                    com.radarnav.core.osm.OsmCameraSource.parse(com.radarnav.core.net.LenientJson.parseToJsonElement(r.readText()))
                }
            }.getOrNull()
        },
    )
    /** EGM'nin resmî EDS noktaları (uygulamaya gömülü; haftada bir EGM sayfasından güncellenir). */
    val egmEds = com.radarnav.core.egm.EgmEdsSource(
        http, FileTextStore(File(app.filesDir, "egm")),
        snapshot = { runCatching { app.assets.open(EGM_SNAPSHOT_ASSET).bufferedReader().use { it.readText() } }.getOrNull() },
    )
    val hazardFinder = HazardFinder(
        ministry, districts,
        cameras = com.radarnav.core.egm.CompositeCameraSource(listOf(egmEds, osmCameras)),
        // Bakanlık yanıtları kalıcı: sunucu çöktüğünde ya da uygulama yeniden açıldığında son bilinen radarlar kullanılır
        store = FileTextStore(File(app.filesDir, "ministry")),
    )
    val placeSearch = PlaceSearch(http)
    val speaker = Speaker(app)
    val gemini = com.radarnav.assistant.GeminiResolver(http, settings)
    val voice = com.radarnav.audio.VoiceOutput(this)
    val location = LocationSource(app)
    val heading = com.radarnav.nav.HeadingProvider(app)

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<UiEvent> = _events
    fun emit(event: UiEvent) {
        _events.tryEmit(event)
    }

    val controller = NavigationController(this)
    val assistant = AssistantManager(this)

    init {
        // İl/ilçe listesi neredeyse hiç değişmez: bir kez, arka planda ve sunucuyu yormadan
        // (istekler arası 1,5 sn) tamamını indirip 180 gün sakla. Yarıda kalırsa sonraki açılışta sürer.
        scope.launch {
            kotlinx.coroutines.delay(20_000)
            runCatching { if (!districts.isComplete()) districts.prefetchAll(delayMs = 1_500) }
        }
    }

    companion object {
        const val USER_AGENT = "RadarNav/0.1 (Android; https://github.com/emocantemocan/radar)"
        const val OSM_SNAPSHOT_ASSET = "osm/speed_cameras_tr.json"
        const val EGM_SNAPSHOT_ASSET = "egm/eds_tr.json"
    }
}

private class FileDistrictCache(private val file: File) : DistrictCache {
    override fun read(): String? = runCatching { if (file.exists()) file.readText() else null }.getOrNull()
    override fun write(content: String) {
        runCatching { file.writeText(content) }
    }
}

/** Her anahtar ayrı dosya (anahtarlar yalnızca harf, rakam, _ ve - içerir). */
private class FileTextStore(private val dir: File) : com.radarnav.core.osm.TextStore {
    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_-]"), "_"))
    override fun read(key: String): String? = runCatching { file(key).takeIf { it.exists() }?.readText() }.getOrNull()
    override fun write(key: String, value: String) {
        runCatching {
            dir.mkdirs()
            // Yarım yazılmış dosya kalmasın
            val tmp = File(dir, "${file(key).name}.tmp")
            tmp.writeText(value)
            tmp.renameTo(file(key))
        }
    }
}
