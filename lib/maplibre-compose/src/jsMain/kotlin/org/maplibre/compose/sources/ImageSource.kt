package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import js.objects.unsafeJso
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.gljs.GlJsImageSource
import org.maplibre.compose.gljs.UpdateImageOptions
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.toDataUrl
import org.maplibre.spatialk.geojson.Position

/** MapLibre GL JS names images by URL, so a bitmap is encoded to a `data:` URL on every update. */
public actual class ImageSource : Source {

  private var bounds: PositionQuad
  private var url: String

  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) : super(id) {
    bounds = position
    url = image.toDataUrl()
  }

  public actual constructor(id: String, position: PositionQuad, uri: String) : super(id) {
    bounds = position
    url = uri
  }

  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("url", url)
    putJsonArray("coordinates") { bounds.toCorners().forEach { add(it.toCoordinateJson()) } }
  }

  /** Null when the style has unloaded. */
  internal fun attachedCorners(): List<Position>? =
    liveSource<GlJsImageSource>()?.coordinates?.map {
      Position(longitude = it[0], latitude = it[1])
    }

  public actual fun setBounds(bounds: PositionQuad) {
    this.bounds = bounds
    val coordinates = bounds.toJsCoordinates()
    mutate { liveSource<GlJsImageSource>()?.setCoordinates(coordinates) }
  }

  public actual fun setImage(image: ImageBitmap) {
    setUri(image.toDataUrl())
  }

  public actual fun setUri(uri: String) {
    url = uri
    val options = unsafeJso<UpdateImageOptions> { this.url = uri }
    mutate { liveSource<GlJsImageSource>()?.updateImage(options) }
  }

  private fun PositionQuad.toJsCoordinates(): Array<Array<Double>> =
    toCorners().map { arrayOf(it.longitude, it.latitude) }.toTypedArray()
}

/** The order MapLibre expects: top left, top right, bottom right, bottom left. */
private fun PositionQuad.toCorners(): List<Position> =
  listOf(topLeft, topRight, bottomRight, bottomLeft)

private fun Position.toCoordinateJson(): JsonArray = buildJsonArray {
  add(longitude)
  add(latitude)
}
