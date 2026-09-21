package org.maplibre.compose.style

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.layers.Anchor
import org.maplibre.compose.layers.FeaturesClickHandler
import org.maplibre.compose.layers.LayerProperty
import org.maplibre.compose.sources.Source

/** Prepared in composition; retained state changes only when the node's updater applies it. */
internal data class PreparedLayerDefinition(
  val id: String,
  val type: String,
  val sourceId: String? = null,
  val properties: Map<StyleProperty, JsonElement> = emptyMap(),
  val imageProperties: Map<StyleProperty, LayerProperty<*>> = emptyMap(),
  val json: JsonObject? = null,
  val unsupportedProperties: Map<String, String> = emptyMap(),
  val filterUnsupportedProperties: Boolean = false,
)

internal class LayerNode(initial: PreparedLayerDefinition, var anchor: Anchor) : MapNode {
  val registration = Any()
  private var prepared: PreparedLayerDefinition? = null
  private var snapshot: ResolvedLayerDefinition? = null

  val definition: ResolvedLayerDefinition
    get() = checkNotNull(snapshot)

  val imageProperties: Map<StyleProperty, LayerProperty<*>>
    get() = checkNotNull(prepared).imageProperties

  internal var source: Source? = null
  internal var onClick: FeaturesClickHandler? = null
  internal var onLongClick: FeaturesClickHandler? = null
  internal var onDoubleClick: FeaturesClickHandler? = null
  internal var clickGroup: Any? = null
  internal var hitPadding: Dp = 0.dp

  init {
    updateDefinition(initial)
  }

  fun updateDefinition(next: PreparedLayerDefinition) {
    val previous = prepared
    if (next == previous) return
    val rawChanged = previous == null || next.json != previous.json
    val base = if (rawChanged) next.json.orEmpty() else definition.value
    var root = if (rawChanged) base.toMutableMap() else null
    var layout: MutableMap<String, JsonElement>? = null
    var paint: MutableMap<String, JsonElement>? = null

    fun putRoot(name: String, value: JsonElement?) {
      val current = root ?: base
      if (current[name] == value) return
      val changed = root ?: base.toMutableMap().also { root = it }
      if (value == null) changed.remove(name) else changed[name] = value
    }

    fun put(path: StyleProperty, value: JsonElement?) {
      if (path.section == null) {
        putRoot(path.name, value)
        return
      }
      val section = (base[path.section] as? JsonObject).orEmpty()
      val current = (if (path.section == "layout") layout else paint) ?: section
      if (current[path.name] == value) return
      val changed =
        (if (path.section == "layout") layout else paint)
          ?: section.toMutableMap().also {
            if (path.section == "layout") layout = it else paint = it
          }
      if (value == null) changed.remove(path.name) else changed[path.name] = value
    }

    if (!rawChanged) {
      previous.properties.keys.forEach { path ->
        if (path !in next.properties) put(path, null)
      }
    }
    next.properties.forEach { (path, value) ->
      if (rawChanged || previous.properties[path] != value) put(path, value)
    }
    layout?.let {
      putRoot(
        "layout",
        if (it.isNotEmpty() || next.json?.containsKey("layout") == true) JsonObject(it) else null,
      )
    }
    paint?.let {
      putRoot(
        "paint",
        if (it.isNotEmpty() || next.json?.containsKey("paint") == true) JsonObject(it) else null,
      )
    }
    putRoot("id", JsonPrimitive(next.id))
    putRoot("type", JsonPrimitive(next.type))
    putRoot("source", next.sourceId?.let(::JsonPrimitive))
    val resolved =
      ResolvedLayerDefinition(
        next.id,
        next.type,
        next.sourceId,
        root?.let(::JsonObject) ?: definition.value,
        unsupportedProperties = next.unsupportedProperties,
        filterUnsupportedProperties = next.filterUnsupportedProperties,
        scaleTransitions = next.json == null,
      )
    if (resolved != snapshot) snapshot = resolved
    prepared = next
  }

  override fun toString(): String = "LayerNode(layer=${definition.id}, anchor=$anchor)"
}
