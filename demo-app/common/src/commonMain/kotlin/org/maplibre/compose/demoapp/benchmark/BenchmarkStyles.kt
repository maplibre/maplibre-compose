package org.maplibre.compose.demoapp.benchmark

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

internal val BenchmarkOrigin = Position(0.0, 0.0)

internal fun benchmarkCamera(x: Double) =
  CameraPosition(target = Position(x * 0.002, 0.0), zoom = 15.0)

/**
 * Hues stay clear of the analysis marker bands, which are half-degrees in OpenCV's scale: red
 * 0-10/170-180, cyan 80-100, gate green 40-79, and gate magenta 140-169. A generated layer in one
 * of those bands would be mistaken for a marker or a gate.
 */
internal val BenchmarkPalette =
  listOf(
    Color(0xFF4F7CFF),
    Color(0xFF6C5CE7),
    Color(0xFFC879FF),
    Color(0xFFD99A2B),
    Color(0xFF2B5CD9),
    Color(0xFF7C5CFF),
  )

/** The palette color at [index] as the style JSON writes it. */
internal fun styleColorHex(index: Int): String =
  BenchmarkPalette[index.mod(BenchmarkPalette.size)].toHexString()

private fun Color.toHexString(): String {
  fun channel(value: Float) =
    (value * 255f).roundToInt().coerceIn(0, 255).toString(16).padStart(2, '0')
  return "#" + channel(red) + channel(green) + channel(blue)
}

/**
 * A network-free reference marker with optional deterministic gray geometry to increase work. Pass
 * `reference = false` when the scenario composes its own reference marker on top.
 */
internal fun benchmarkStyle(load: Int, reference: Boolean = true): BaseStyle =
  BaseStyle.Json(
    generatedStyle(load = load, sources = 0, features = 0, layers = 0, reference = reference)
  )

/**
 * A deterministic generated style: a background, optional load circles, [layers] generated layers
 * cycling through circle, line, fill, and symbol types over [sources] generated GeoJSON sources,
 * and a red reference marker on top unless [reference] is false. [palette] selects a variant's
 * colors. [symbols] image IDs are `bench-icon-<n>`; the caller registers them.
 */
internal fun generatedStyle(
  load: Int,
  sources: Int,
  features: Int,
  layers: Int,
  palette: Int = 0,
  symbols: Int = 0,
  reference: Boolean = true,
): String {
  require((layers == 0 && symbols == 0) || sources > 0) {
    "Generated layers and symbols need at least one source"
  }
  val style = buildJsonObject {
    put("version", 8)
    putJsonObject("sources") {
      if (reference) {
        putJsonObject("point") {
          put("type", "geojson")
          putJsonObject("data") {
            put("type", "Point")
            putJsonArray("coordinates") {
              add(0.0)
              add(0.0)
            }
          }
        }
      }
      if (load > 0) {
        putJsonObject("load") {
          put("type", "geojson")
          put("data", Json.parseToJsonElement(generatedPointCollection(load, seed = 0)))
        }
      }
      repeat(sources) { source ->
        putJsonObject("gen-$source") {
          put("type", "geojson")
          put("data", Json.parseToJsonElement(generatedFeatureCollection(features, seed = source)))
        }
      }
    }
    putJsonArray("layers") {
      add(
        buildJsonObject {
          put("id", "background")
          put("type", "background")
          putJsonObject("paint") { put("background-color", "#202020") }
        }
      )
      if (load > 0) add(generatedCircleLayer("load", "load", "#505050", 8.0))
      repeat(layers) { index ->
        val color = styleColorHex(index + palette)
        val source = "gen-${index % sources}"
        // Without registered icons, symbol layers would be inert; cycle only drawable types.
        when (if (symbols == 0) index % 3 else index % 4) {
          0 -> add(generatedCircleLayer("gen-$index", source, color, 6.0 + index % 4))
          1 ->
            add(
              buildJsonObject {
                put("id", "gen-$index")
                put("type", "line")
                put("source", source)
                putJsonObject("paint") {
                  put("line-color", color)
                  put("line-width", 1.5)
                }
              }
            )
          2 ->
            add(
              buildJsonObject {
                put("id", "gen-$index")
                put("type", "fill")
                put("source", source)
                putJsonObject("paint") {
                  put("fill-color", color)
                  put("fill-opacity", 0.35)
                }
              }
            )
          else ->
            add(
              buildJsonObject {
                put("id", "gen-$index")
                put("type", "symbol")
                put("source", source)
                putJsonObject("layout") {
                  put("icon-image", "bench-icon-${index % symbols}")
                  put("icon-size", 1)
                }
              }
            )
        }
      }
      repeat(symbols) { index ->
        add(
          buildJsonObject {
            put("id", "gen-symbol-$index")
            put("type", "symbol")
            put("source", "gen-${index % sources}")
            putJsonObject("layout") {
              put("icon-image", "bench-icon-$index")
              put("icon-size", 1)
            }
          }
        )
      }
      if (reference) add(generatedCircleLayer("point", "point", "#ff0000", 10.0))
    }
  }
  return Json.encodeToString(JsonObject.serializer(), style)
}

/** A style with circle layers the mutation workload can change through their handles. */
internal fun styleMutationStyle(load: Int, layers: Int): String {
  val style = buildJsonObject {
    put("version", 8)
    putJsonObject("sources") {
      if (load > 0) {
        putJsonObject("load") {
          put("type", "geojson")
          put("data", Json.parseToJsonElement(generatedPointCollection(load, seed = 0)))
        }
      }
      putJsonObject("mutate") {
        put("type", "geojson")
        put("data", Json.parseToJsonElement(generatedFeatureCollection(600, seed = 7)))
      }
    }
    putJsonArray("layers") {
      add(
        buildJsonObject {
          put("id", "background")
          put("type", "background")
          putJsonObject("paint") { put("background-color", "#202020") }
        }
      )
      if (load > 0) add(generatedCircleLayer("load", "load", "#505050", 8.0))
      repeat(layers) { index ->
        add(generatedCircleLayer("base-$index", "mutate", styleColorHex(index), 5.0))
      }
    }
  }
  return Json.encodeToString(JsonObject.serializer(), style)
}

private fun generatedCircleLayer(
  id: String,
  source: String,
  color: String,
  radius: Double,
): JsonObject = buildJsonObject {
  put("id", id)
  put("type", "circle")
  put("source", source)
  putJsonObject("paint") {
    put("circle-color", color)
    put("circle-radius", radius)
  }
}

/** A GeoJSON document with [features] mixed point, line, and polygon features. */
internal fun generatedFeatureCollection(features: Int, seed: Int): String {
  val out = StringBuilder("""{"type":"FeatureCollection","features":[""")
  repeat(features) { index ->
    if (index > 0) out.append(',')
    out.append(generatedFeature(index, seed))
  }
  out.append("]}")
  return out.toString()
}

/** A GeoJSON document with [features] point features, matching the historical load geometry. */
internal fun generatedPointCollection(features: Int, seed: Int): String {
  val out = StringBuilder("""{"type":"MultiPoint","coordinates":[""")
  repeat(features) { index ->
    if (index > 0) out.append(',')
    appendPosition(out, featureX(index, seed), featureY(index, seed))
  }
  out.append("]}")
  return out.toString()
}

private fun generatedFeature(index: Int, seed: Int): String {
  val x = featureX(index, seed)
  val y = featureY(index, seed)
  return when (index % 5) {
    3 -> {
      val out =
        StringBuilder(
          """{"type":"Feature","properties":{},"geometry":{"type":"LineString","coordinates":["""
        )
      appendPosition(out, x, y)
      out.append(',')
      appendPosition(out, featureX(index + 7919, seed), featureY(index + 7919, seed))
      out.append("]}}")
      out.toString()
    }
    4 -> {
      val out =
        StringBuilder(
          """{"type":"Feature","properties":{},"geometry":{"type":"Polygon","coordinates":"""
        )
      out.append("[[")
      appendPosition(out, x - 0.0004, y - 0.0004)
      out.append(',')
      appendPosition(out, x + 0.0004, y - 0.0004)
      out.append(',')
      appendPosition(out, x + 0.0004, y + 0.0004)
      out.append(',')
      appendPosition(out, x - 0.0004, y + 0.0004)
      out.append(',')
      appendPosition(out, x - 0.0004, y - 0.0004)
      out.append("]]}}")
      out.toString()
    }
    else ->
      """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${number(x)},${number(y)}]}}"""
  }
}

private fun featureX(index: Int, seed: Int) = ((index * 73 + seed * 131) % 997 / 997.0 - 0.5) * 0.02

private fun featureY(index: Int, seed: Int) =
  ((index * 137 + seed * 193) % 991 / 991.0 - 0.5) * 0.01

private fun appendPosition(out: StringBuilder, x: Double, y: Double) {
  out.append('[').append(number(x)).append(',').append(number(y)).append(']')
}

/** Formats a fixed-point decimal; Double.toString would emit JSON-invalid scientific notation. */
private fun number(value: Double): String {
  val scaled = round(value * 1e6).toLong()
  val sign = if (scaled < 0) "-" else ""
  val magnitude = abs(scaled)
  val fraction = magnitude % 1_000_000
  val whole = magnitude / 1_000_000
  if (fraction == 0L) return "$sign$whole"
  return sign + whole + "." + fraction.toString().padStart(6, '0').trimEnd('0')
}

/** A deterministic opaque bitmap for style-image workloads. */
internal fun generatedBitmap(sizePx: Int, seed: Int): ImageBitmap {
  val bitmap = ImageBitmap(sizePx, sizePx)
  val canvas = Canvas(bitmap)
  val paint = Paint().apply { color = BenchmarkPalette[seed.mod(BenchmarkPalette.size)] }
  canvas.drawRect(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), paint)
  canvas.drawCircle(
    Offset(sizePx * 0.5f, sizePx * 0.5f),
    sizePx * 0.25f,
    Paint().apply { color = Color.White },
  )
  return bitmap
}
