package org.maplibre.compose.sources

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.maplibre.compose.style.ImageSnapshot
import org.maplibre.compose.style.SourceDefinition
import org.maplibre.compose.util.PositionQuad
import org.maplibre.spatialk.geojson.Position

/** A map data source of an image placed at a given position. */
public class ImageSource : Source {

  private var bounds: PositionQuad
  private var url: String

  /** The pixels this source draws, or null when a URL names them. */
  private var image: ImageBitmap?

  /** Create an ImageSource from coordinates and a bitmap image. */
  public constructor(id: String, position: PositionQuad, image: ImageBitmap) : super(id) {
    bounds = position
    url = ""
    this.image = image
  }

  /** Create an ImageSource from coordinates and an image URI. */
  public constructor(id: String, position: PositionQuad, uri: String) : super(id) {
    bounds = position
    url = uri
    image = null
  }

  /**
   * Adds the source with its pixels when it has them; source JSON can only name a URL, so a
   * pixel-backed source added from [toJson] would be added empty.
   */
  override fun definition(): SourceDefinition =
    SourceDefinition.Image(id, toJson(), bounds.toCorners(), image?.let(ImageSnapshot::capture))

  /** The URL form of this source; a pixel-backed source reports an empty `url` here. */
  override fun toJson(): JsonObject = buildJsonObject {
    put("type", "image")
    put("url", url)
    putJsonArray("coordinates") { bounds.toCorners().forEach { add(it.toCoordinateJson()) } }
  }

  internal fun setDesiredBounds(bounds: PositionQuad) {
    this.bounds = bounds
  }

  internal fun setDesiredImage(image: ImageBitmap) {
    url = ""
    this.image = image
  }

  internal fun setDesiredUri(uri: String) {
    url = uri
    image = null
  }
}

/** The order MapLibre expects: top left, top right, bottom right, bottom left. */
private fun PositionQuad.toCorners(): List<Position> =
  listOf(topLeft, topRight, bottomRight, bottomLeft)

private fun Position.toCoordinateJson(): JsonArray = buildJsonArray {
  add(longitude)
  add(latitude)
}

/**
 * Remember a new [ImageSource] from the given [uri].
 *
 * Recomposition publishes a new immutable source definition when [position] or [uri] changes.
 */
@Composable
public fun rememberImageSource(position: PositionQuad, uri: String): ImageSource =
  rememberUserSource(
    factory = { ImageSource(id = it, position = position, uri = uri) },
    update = {
      setDesiredBounds(position)
      setDesiredUri(uri)
    },
  )

/**
 * Remember a new [ImageSource] from the given [bitmap].
 *
 * Recomposition publishes a new immutable source definition when [position] or [bitmap] changes.
 */
@Composable
public fun rememberImageSource(position: PositionQuad, bitmap: ImageBitmap): ImageSource =
  rememberUserSource(
    factory = { ImageSource(id = it, position = position, image = bitmap) },
    update = {
      setDesiredBounds(position)
      setDesiredImage(bitmap)
    },
  )
