package com.radarnav.map

import com.radarnav.data.Settings
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.android.maps.Style

/** Ayarlara göre harita stili: gömülü gece vektör stili, özel stil adresi ya da resim karoları. */
object MapStyles {
    const val NIGHT_STYLE = "asset://map/night.json"

    fun builder(settings: Settings): Style.Builder = when (settings.mapProvider) {
        "vector_custom" -> Style.Builder().fromUri(settings.mapStyleUrl ?: NIGHT_STYLE)
        "carto", "custom" -> Style.Builder().fromJson(rasterStyle(settings.tileUrl, settings.darkMap))
        else -> Style.Builder().fromUri(NIGHT_STYLE)
    }

    /** Stil değişti mi diye karşılaştırmak için anahtar. */
    fun key(settings: Settings): String = when (settings.mapProvider) {
        "vector_custom" -> "v:" + settings.mapStyleUrl
        "carto", "custom" -> "r:" + settings.tileUrl + settings.darkMap
        else -> "night"
    } + "|t:" + settings.terrainShading

    /** Resim karolu stil; [dark] ise karolar ters çevrilip lacivert tona kaydırılır. */
    private fun rasterStyle(template: String, dark: Boolean): String {
        val urls = if (template.contains("{s}")) "abcd".map { template.replace("{s}", it.toString()) } else listOf(template)
        return buildJsonObject {
            put("version", 8)
            putJsonObject("sources") {
                putJsonObject("raster") {
                    put("type", "raster")
                    put("tileSize", 256)
                    put("maxzoom", 19)
                    put("attribution", "© OpenStreetMap katkıcıları")
                    putJsonArray("tiles") { urls.forEach { add(it.replace("{r}", "")) } }
                }
            }
            putJsonArray("layers") {
                addJsonObject {
                    put("id", "background")
                    put("type", "background")
                    putJsonObject("paint") { put("background-color", "#0B1220") }
                }
                addJsonObject {
                    put("id", "raster")
                    put("type", "raster")
                    put("source", "raster")
                    if (dark) {
                        putJsonObject("paint") {
                            put("raster-brightness-min", 0.92)
                            put("raster-brightness-max", 0.06)
                            put("raster-hue-rotate", 200)
                            put("raster-saturation", -0.35)
                            put("raster-contrast", 0.15)
                        }
                    }
                }
            }
        }.toString()
    }
}
