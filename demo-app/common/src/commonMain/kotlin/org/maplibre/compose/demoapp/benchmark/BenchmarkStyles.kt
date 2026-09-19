package org.maplibre.compose.demoapp.benchmark

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlinx.serialization.json.*
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

internal val BenchmarkOrigin = Position(-122.4194, 37.7749)
internal val BenchmarkColors = listOf(Color(0xff4f7cff), Color(0xffd99a2b))
internal val BenchmarkColorStrings = listOf("#4f7cff", "#d99a2b")

internal fun benchmarkCamera(x: Double) =
  CameraPosition(
    target = Position(BenchmarkOrigin.longitude + x * 0.002, BenchmarkOrigin.latitude),
    zoom = 15.0,
  )

/** Prepared bytes and style variants; creation and JSON parsing happen before measurement. */
internal class BenchmarkFixture(
  val config: BenchmarkConfig,
  val data: List<GeoJsonData.JsonString>,
  val baseStyles: List<BaseStyle>,
  val report: JsonObject,
) {
  val images =
    if (config.scenario == BenchmarkScenario.Images)
      BenchmarkColors.map { color ->
        ImageBitmap(32, 32).also { bitmap ->
          Canvas(bitmap).drawRect(0f, 0f, 32f, 32f, Paint().apply { this.color = color })
        }
      }
    else emptyList()
  val line
    get() = config.scene == BenchmarkScene.Route
}

internal suspend fun loadBenchmarkFixture(config: BenchmarkConfig): BenchmarkFixture {
  val root = "files/benchmarks/"
  val manifest =
    BenchmarkJson.parseToJsonElement(Res.readBytes(root + "manifest.json").decodeToString())
      .jsonObject
  val hasData = config.scene !in setOf(BenchmarkScene.Minimal, BenchmarkScene.Basemap)
  val data =
    if (hasData)
      List(2) { index ->
        GeoJsonData.JsonString(
          Res.readBytes(root + "${config.scene.id}-$index.geojson").decodeToString()
        )
      }
    else emptyList()
  val composeContent =
    config.implementation == BenchmarkImplementation.Declarative &&
      config.scenario != BenchmarkScenario.Style &&
      hasData
  val styles =
    List(2) { variant ->
      BaseStyle.Json(
        buildJsonObject {
          put("version", 8)
          putJsonObject("transition") {
            put("duration", 0)
            put("delay", 0)
          }
          if (config.scene == BenchmarkScene.Basemap) {
            put(
              "glyphs",
              Res.getUri(root + "basemap/glyphs/noto_sans_regular/0-255.pbf")
                .replace("noto_sans_regular/0-255.pbf", "{fontstack}/{range}.pbf"),
            )
          }
          putJsonObject("sources") {
            if (hasData && !composeContent)
              putJsonObject("data") {
                put("type", "geojson")
                put("data", BenchmarkJson.parseToJsonElement(data[0].json))
              }
            if (config.scene == BenchmarkScene.Basemap)
              putJsonObject("basemap") {
                put("type", "vector")
                put("minzoom", 14)
                put("maxzoom", 14)
                putJsonArray("bounds") {
                  add(-122.4755859375)
                  add(37.735969208590504)
                  add(-122.36572265625)
                  add(37.82280243352756)
                }
                putJsonArray("tiles") {
                  add(
                    Res.getUri(root + "basemap/tiles/14/2620/6332.pbf")
                      .replace("14/2620/6332.pbf", "{z}/{x}/{y}.pbf")
                  )
                }
                put("attribution", "© OpenStreetMap contributors")
              }
            if (config.overlays > 0 && !composeContent)
              putJsonObject("reference") {
                put("type", "geojson")
                putJsonObject("data") {
                  put("type", "Point")
                  putJsonArray("coordinates") {
                    add(BenchmarkOrigin.longitude)
                    add(BenchmarkOrigin.latitude)
                  }
                }
              }
          }
          putJsonArray("layers") {
            add(
              buildJsonObject {
                put("id", "background")
                put("type", "background")
                putJsonObject("paint") {
                  put("background-color", if (variant == 0) "#202020" else "#303030")
                }
              }
            )
            if (config.scene == BenchmarkScene.Basemap) basemapLayers().forEach { add(it) }
            if (hasData && !composeContent)
              repeat(config.layers) { index ->
                add(
                  dataLayer(
                    "workload-$index",
                    config.scene == BenchmarkScene.Route,
                    config.scenario == BenchmarkScenario.Images,
                  )
                )
              }
            if (config.overlays > 0 && !composeContent)
              add(
                buildJsonObject {
                  put("id", "reference")
                  put("type", "circle")
                  put("source", "reference")
                  putJsonObject("paint") {
                    put("circle-color", "#ff0000")
                    put("circle-radius", 10)
                  }
                }
              )
          }
        }
          .toString()
      )
    }
  val report = buildJsonObject {
    put("fixtureVersion", 1)
    put("fixtureSha256", manifest.getValue("sha256"))
    put("scene", config.scene.id)
    put("overlays", config.overlays)
    put("dataLayers", if (hasData) config.layers else 0)
    put("hiddenSourceAnchor", config.scenario == BenchmarkScenario.Layers)
    if (config.scenario == BenchmarkScenario.Images) {
      put("imageCount", 1)
      put("imageWidthPx", 32)
      put("imageHeightPx", 32)
    }
    if (hasData) {
      put("geometry", manifest.getValue("fixtures").jsonObject.getValue(config.scene.id))
      put("data", manifest.getValue("assets").jsonObject.getValue("${config.scene.id}-0.geojson"))
    }
    if (config.scene == BenchmarkScene.Basemap) {
      put("tiles", 25)
      put("styleLayers", basemapLayers().size + 1)
      put(
        "assetBytes",
        manifest
          .getValue("assets")
          .jsonObject
          .filterKeys { it.startsWith("basemap/") }
          .values
          .sumOf { it.jsonObject.getValue("bytes").jsonPrimitive.long },
      )
    }
  }
  return BenchmarkFixture(config, data, styles, report)
}

private fun dataLayer(id: String, line: Boolean, image: Boolean) = buildJsonObject {
  put("id", id)
  put("type", if (image) "symbol" else if (line) "line" else "circle")
  put("source", "data")
  if (image)
    putJsonObject("layout") {
      put("icon-image", "workload-image")
      put("icon-allow-overlap", true)
    }
  else
    putJsonObject("paint") {
      put(if (line) "line-color" else "circle-color", BenchmarkColorStrings[0])
      put(if (line) "line-width" else "circle-radius", if (line) 3 else 5)
    }
}

/** A compact streets style over real Shortbread vector tiles. Text uses bundled glyphs. */
private fun basemapLayers(): List<JsonObject> {
  fun layer(
    id: String,
    sourceLayer: String,
    type: String,
    paint: JsonObject,
    layout: JsonObject? = null,
  ) = buildJsonObject {
    put("id", id)
    put("source", "basemap")
    put("source-layer", sourceLayer)
    put("type", type)
    put("paint", paint)
    if (layout != null) put("layout", layout)
  }
  return listOf(
    layer("land", "land", "fill", buildJsonObject { put("fill-color", "#35443c") }),
    layer("water", "water_polygons", "fill", buildJsonObject { put("fill-color", "#253e65") }),
    layer("buildings", "buildings", "fill", buildJsonObject { put("fill-color", "#555052") }),
    layer(
      "road-casing",
      "streets",
      "line",
      buildJsonObject {
        put("line-color", "#171717")
        put("line-width", 5)
      },
    ),
    layer(
      "roads",
      "streets",
      "line",
      buildJsonObject {
        put("line-color", "#a2a090")
        put("line-width", 3)
      },
    ),
    layer(
      "road-labels",
      "street_labels",
      "symbol",
      buildJsonObject {
        put("text-color", "#ffffff")
        put("text-halo-color", "#202020")
        put("text-halo-width", 1)
      },
      buildJsonObject {
        put("symbol-placement", "line")
        put(
          "text-field",
          buildJsonArray {
            add("coalesce")
            add(
              buildJsonArray {
                add("get")
                add("name_en")
              }
            )
            add(
              buildJsonArray {
                add("get")
                add("name")
              }
            )
          },
        )
        putJsonArray("text-font") { add("noto_sans_regular") }
        put("text-size", 12)
      },
    ),
  )
}
