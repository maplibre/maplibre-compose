package org.maplibre.compose.layers

import org.maplibre.compose.style.DesktopStyle
import org.maplibre.compose.util.jsonEscape

internal actual sealed class Layer {
  @Suppress("UNREACHABLE_CODE") abstract val impl: Nothing

  internal abstract val layerId: String
  actual val id: String get() = layerId

  // Reference to the style this layer belongs to (null if not yet added to a style)
  internal var style: DesktopStyle? = null

  // The id of the layer this layer was inserted before (null = topmost)
  internal var insertedBefore: String? = null

  internal val layoutProps = mutableMapOf<String, String>()
  internal val paintProps = mutableMapOf<String, String>()

  private var _minZoom: Float = 0f
  private var _maxZoom: Float = 22f
  private var _visible: Boolean = true

  actual var minZoom: Float
    get() = _minZoom
    set(value) {
      _minZoom = value
      style?.updateLayer(this)
    }

  actual var maxZoom: Float
    get() = _maxZoom
    set(value) {
      _maxZoom = value
      style?.updateLayer(this)
    }

  actual var visible: Boolean
    get() = _visible
    set(value) {
      _visible = value
      layoutProps["visibility"] = if (value) "\"visible\"" else "\"none\""
      style?.updateLayer(this)
    }

  internal fun setLayoutProp(key: String, jsonValue: String) {
    layoutProps[key] = jsonValue
    style?.updateLayer(this)
  }

  internal fun setPaintProp(key: String, jsonValue: String) {
    paintProps[key] = jsonValue
    style?.updateLayer(this)
  }

  /** Returns the MapLibre style type string for this layer (e.g. "line", "fill"). */
  internal abstract fun layerType(): String

  /** Returns the source ID for this layer, or null if none. */
  internal open fun sourceId(): String? = null

  /** Returns the source-layer ID for this layer, or null if none. */
  internal open fun sourceLayerString(): String? = null

  /** Returns the filter expression JSON string for this layer, or null if none. */
  internal open fun filterJson(): String? = null

  /** Serializes the full layer spec to a MapLibre style JSON object string. */
  internal open fun toJson(): String = buildString {
    append("""{"id":""")
    append(jsonEscape(id))
    append(""","type":""")
    append(jsonEscape(layerType()))
    sourceId()?.let {
      append(""","source":""")
      append(jsonEscape(it))
    }
    sourceLayerString()?.takeIf { it.isNotEmpty() }?.let {
      append(""","source-layer":""")
      append(jsonEscape(it))
    }
    if (_minZoom != 0f) {
      append(""","minzoom":""")
      append(_minZoom)
    }
    if (_maxZoom != 22f) {
      append(""","maxzoom":""")
      append(_maxZoom)
    }
    filterJson()?.let {
      append(""","filter":""")
      append(it)
    }
    if (layoutProps.isNotEmpty()) {
      append(""","layout":{""")
      layoutProps.entries.joinTo(this, ",") { (k, v) -> "${jsonEscape(k)}:$v" }
      append("}")
    }
    if (paintProps.isNotEmpty()) {
      append(""","paint":{""")
      paintProps.entries.joinTo(this, ",") { (k, v) -> "${jsonEscape(k)}:$v" }
      append("}")
    }
    append("}")
  }

  override fun toString() = "${this::class.simpleName}(id=\"$id\")"
}
