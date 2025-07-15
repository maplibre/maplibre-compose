package org.maplibre.compose.style.layer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Updater
import androidx.compose.runtime.key
import org.maplibre.compose.core.layer.Layer
import org.maplibre.compose.style.FeaturesClickHandler
import org.maplibre.compose.style.MaplibreComposable
import org.maplibre.compose.style.engine.LayerNode
import org.maplibre.compose.style.engine.LocalStyleNode
import org.maplibre.compose.style.engine.MapNodeApplier

@Composable
@MaplibreComposable
internal fun <T : Layer> LayerNode(
  factory: () -> T,
  update: Updater<LayerNode<T>>.() -> Unit,
  onClick: FeaturesClickHandler?,
  onLongClick: FeaturesClickHandler?,
) {
  val anchor = LocalAnchor.current
  val node = LocalStyleNode.current

  key(factory, anchor) {
    ComposeNode<LayerNode<T>, MapNodeApplier>(
      factory = { LayerNode(layer = factory(), anchor = anchor) },
      update = {
        if (!node.style.isUnloaded) {
          update()
          set(onClick) { this.onClick = it }
          set(onLongClick) { this.onLongClick = it }
        }
      },
    )
  }
}
