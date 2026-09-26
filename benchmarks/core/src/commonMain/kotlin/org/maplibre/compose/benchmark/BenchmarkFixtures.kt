package org.maplibre.compose.benchmark

import kotlin.math.*
import kotlinx.serialization.json.*

val BenchmarkColorStrings = listOf("#4f7cff", "#d99a2b")
const val BenchmarkLatitude = 37.7749
const val BenchmarkLongitude = -122.4194

data class BenchmarkCamera(
  val latitude: Double,
  val longitude: Double,
  val zoom: Double,
  val bearing: Double = 0.0,
  val tilt: Double = 0.0,
)

fun benchmarkCamera(x: Double) =
  BenchmarkCamera(BenchmarkLatitude, BenchmarkLongitude + x * 0.002, 15.0)

fun tourCamera(progress: Double) =
  benchmarkCamera(-cos(progress * 2 * PI))
    .copy(
      zoom = 15.0 + 0.4 * sin(progress * 2 * PI),
      bearing = 30 * sin(progress * 2 * PI),
      tilt = 30 * (0.5 - 0.5 * cos(progress * 2 * PI)),
    )

class PreparedBenchmarkFixture(
  val config: BenchmarkConfig,
  val data: List<String>,
  val baseStyles: List<String>,
) {
  val line
    get() = config.scene == BenchmarkScene.Route
}

suspend fun loadBenchmarkFixture(
  config: BenchmarkConfig,
  read: suspend (String) -> String,
  uri: (String) -> String,
): PreparedBenchmarkFixture {
  val hasData = config.scene !in setOf(BenchmarkScene.Minimal, BenchmarkScene.Basemap)
  val data =
    if (hasData)
      List(2) { index ->
        read("${config.scene.id}-$index.geojson")
      }
    else emptyList()
  val composeContent =
    config.implementation == BenchmarkImplementation.Declarative &&
      config.scenario != BenchmarkScenario.Style &&
      hasData
  val styles =
    List(2) { variant ->
      buildJsonObject {
        put("version", 8)
        putJsonObject("transition") {
          put("duration", 0)
          put("delay", 0)
        }
        if (config.scene == BenchmarkScene.Basemap) {
          put(
            "glyphs",
            uri("basemap/glyphs/noto_sans_regular/0-255.pbf")
              .replace("noto_sans_regular/0-255.pbf", "{fontstack}/{range}.pbf"),
          )
        }
        putJsonObject("sources") {
          if (hasData && !composeContent)
            putJsonObject("data") {
              put("type", "geojson")
              put("data", BenchmarkJson.parseToJsonElement(data[0]))
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
                  uri("basemap/tiles/14/2620/6332.pbf")
                    .replace("14/2620/6332.pbf", "{z}/{x}/{y}.pbf")
                )
              }
              put("attribution", "© OpenStreetMap contributors")
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
        }
      }
        .toString()
    }
  return PreparedBenchmarkFixture(config, data, styles)
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
