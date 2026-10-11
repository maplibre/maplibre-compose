package org.maplibre.compose.style.internal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.style.StyleOverrides
import org.maplibre.compose.style.StyleOverridesNode
import org.maplibre.compose.util.MaplibreComposable

/** Null inherits; JSON null explicitly removes an optional root object. */
internal data class StyleOverrideDefinition(
  val light: JsonObject? = null,
  val sky: JsonElement? = null,
  val projection: JsonObject? = null,
  val terrain: JsonElement? = null,
  val terrainSource: Source? = null,
)

@Composable
@MaplibreComposable
internal fun StyleOverridesContent(overrides: StyleOverrides) {
  val definition = overrides.definition()
  ComposeNode<StyleOverridesNode, MapNodeApplier>(
    factory = ::StyleOverridesNode,
    update = { set(definition) { this.definition = it } },
  )
}
