package org.maplibre.compose.layers

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import androidx.compose.runtime.Updater
import androidx.compose.runtime.key
import org.maplibre.compose.style.LayerNode
import org.maplibre.compose.style.MapNodeApplier
import org.maplibre.compose.util.FeaturesClickHandler
import org.maplibre.compose.util.MaplibreComposable

/** [recreateKey] replaces the node through the layer manager when a construction key changes. */
@Composable
@MaplibreComposable
internal fun <T : Layer> LayerNode(
  factory: () -> T,
  update: Updater<LayerNode<T>>.() -> Unit,
  onClick: FeaturesClickHandler?,
  onLongClick: FeaturesClickHandler?,
  recreateKey: Any? = Unit,
) {
  val anchor = LocalAnchor.current

  key(factory, anchor, recreateKey) {
    ComposeNode<LayerNode<T>, MapNodeApplier>(
      factory = { LayerNode(layer = factory(), anchor = anchor) },
      // The descriptor buffers properties and the sync publishes handlers, so recording needs no
      // loaded style.
      update = {
        update()
        set(onClick) { this.onClick = it }
        set(onLongClick) { this.onLongClick = it }
      },
    )
  }
}
