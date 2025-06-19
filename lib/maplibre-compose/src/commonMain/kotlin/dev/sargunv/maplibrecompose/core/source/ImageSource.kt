package dev.sargunv.maplibrecompose.core.source

import androidx.compose.ui.graphics.ImageBitmap
import io.github.dellisd.spatialk.geojson.Position

/** A map data source of an image at overlay at a given position. */
public expect class ImageSource : Source {
  public constructor(id: String, data: ImageSourceData)
}

public sealed interface ImageSourceData {
  public val boundingBox: ImageBoundingBox

  public data class Bitmap(
    override val boundingBox: ImageBoundingBox,
    val bitmap: ImageBitmap
  ): ImageSourceData

  public data class Uri(
    override val boundingBox: ImageBoundingBox,
    val uri: String
  ): ImageSourceData
}

public data class ImageBoundingBox(
  val topLeft: Corner,
  val topRight: Corner,
  val bottomRight: Corner,
  val bottomLeft: Corner,
)

public data class Corner(val latitude: Double, val longitude: Double)
