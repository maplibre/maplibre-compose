package org.maplibre.compose.sources

import androidx.compose.ui.graphics.ImageBitmap
import org.maplibre.compose.util.PositionQuad
import org.maplibre.compose.util.jsonEscape

public actual class ImageSource : Source {
  @Suppress("UNREACHABLE_CODE") override val impl: Nothing get() = TODO()
  override val _sourceId: String

  private val position: PositionQuad
  // Either uri or bitmap data (uri takes precedence if set)
  private var uri: String? = null
  private var bitmap: ImageBitmap? = null

  // Set by DesktopStyle to push updates after bounds/image/uri changes
  internal var style: org.maplibre.compose.style.DesktopStyle? = null

  public actual constructor(id: String, position: PositionQuad, image: ImageBitmap) {
    _sourceId = id
    this.position = position
    this.bitmap = image
  }

  public actual constructor(id: String, position: PositionQuad, uri: String) {
    _sourceId = id
    this.position = position
    this.uri = uri
  }

  public actual fun setBounds(bounds: PositionQuad) {
    // TODO: update via style once DesktopStyle supports source update
  }

  public actual fun setImage(image: ImageBitmap) {
    bitmap = image
    // ImageSource with bitmap data does not support live updates on desktop.
    // The bitmap case is not serializable to JSON; use a URI-based ImageSource instead.
  }

  public actual fun setUri(uri: String) {
    this.uri = uri
    bitmap = null
    style?.updateSource(this)
  }

  override fun toJson(): String {
    val resolvedUri = uri ?: error(
      "ImageSource '$_sourceId' was constructed with a bitmap but desktop requires a URI. " +
        "Use ImageSource(id, position, uri) instead."
    )
    return buildString {
      append("""{"type":"image"""")
      append(""","url":${jsonEscape(resolvedUri)}""")
      val tl = position.topLeft
      val tr = position.topRight
      val br = position.bottomRight
      val bl = position.bottomLeft
      append(""","coordinates":[[${tl.longitude},${tl.latitude}],[${tr.longitude},${tr.latitude}],[${br.longitude},${br.latitude}],[${bl.longitude},${bl.latitude}]]""")
      append("}")
    }
  }
}
