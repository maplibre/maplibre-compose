package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Updater
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.LayerNode
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.util.MaplibreComposable

/** [recreateKey] replaces the desired layer node when a construction key changes. */
@Composable
@MaplibreComposable
internal fun <T : Layer> LayerNode(
  id: String,
  factory: () -> T,
  source: Source? = null,
  update: Updater<LayerNode<T>>.() -> Unit,
  onClick: FeaturesClickHandler?,
  onLongClick: FeaturesClickHandler?,
  recreateKey: Any? = Unit,
  onDoubleClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
) {
  require(hitPadding.value.isFinite() && hitPadding.value >= 0f) {
    "hitPadding must be finite and nonnegative"
  }
  val clickGroup = LocalLayerClickGroup.current
  val anchor = LocalAnchor.current

  // The anchor is not part of the node's identity: a predicate anchor built from a fresh lambda on
  // each recomposition must update the node in place, not recreate it and its click registration.
  key(id, source?.id, recreateKey) {
    ComposeNode<LayerNode<T>, MapNodeApplier>(
      factory = { LayerNode(layer = factory(), anchor = anchor) },
      update = {
        update()
        set(source) { this.source = it }
        set(anchor) { this.anchor = it }
        set(onClick) { this.onClick = it }
        set(onLongClick) { this.onLongClick = it }
        set(onDoubleClick) { this.onDoubleClick = it }
        set(hitPadding) { this.hitPadding = it }
        set(clickGroup) { this.clickGroup = it }
      },
    )
  }
}

// Multiple rendering layers can represent one logical click target.
internal val LocalLayerClickGroup = staticCompositionLocalOf<Any?> { null }
