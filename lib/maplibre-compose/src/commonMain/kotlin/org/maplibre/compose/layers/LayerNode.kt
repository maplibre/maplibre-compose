package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Updater
import androidx.compose.runtime.key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.maplibre.compose.map.HoverEvent
import org.maplibre.compose.style.LayerNode
import org.maplibre.compose.style.LocalStyleNode
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable

/** [recreateKey] replaces the desired layer node when a construction key changes. */
@Composable
@MaplibreComposable
internal fun <T : Layer> LayerNode(
  factory: () -> T,
  update: Updater<LayerNode<T>>.() -> Unit,
  onClick: FeaturesClickHandler?,
  onLongClick: FeaturesClickHandler?,
  recreateKey: Any? = Unit,
  onDoubleClick: FeaturesClickHandler? = null,
  onTwoFingerClick: FeaturesClickHandler? = null,
  hitPadding: Dp = 0.dp,
  onHover: ((HoverEvent) -> Unit)? = null,
) {
  require(hitPadding.value.isFinite() && hitPadding.value >= 0f) {
    "hitPadding must be finite and nonnegative"
  }
  val anchor = LocalAnchor.current
  val node = LocalStyleNode.current

  key(factory, anchor, recreateKey) {
    ComposeNode<LayerNode<T>, MapNodeApplier>(
      factory = { LayerNode(layer = factory(), anchor = anchor) },
      update = {
        update()
        set(onClick) { this.onClick = it }
        set(onLongClick) { this.onLongClick = it }
        set(onDoubleClick) { this.onDoubleClick = it }
        set(onTwoFingerClick) { this.onTwoFingerClick = it }
        set(hitPadding) { this.hitPadding = it }
        set(onHover) { this.onHover = it }
      },
    )
    SideEffect { node.scheduleApplyChanges() }
  }
}
