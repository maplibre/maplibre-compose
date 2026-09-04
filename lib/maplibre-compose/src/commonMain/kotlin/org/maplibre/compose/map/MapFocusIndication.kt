package org.maplibre.compose.map

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Marks the start and end of the map consuming the keys that pan, zoom, rotate, and tilt. */
internal sealed interface EngagedInteraction : Interaction {
  object Engage : EngagedInteraction

  object Disengage : EngagedInteraction
}

/**
 * Draws a ring inside the edge of the map while the map holds focus and the input mode is keyboard.
 * The ring is thicker while the map is engaged. A dark stroke over a light halo reads on light and
 * dark basemaps.
 */
internal object MapFocusIndication : IndicationNodeFactory {
  override fun create(interactionSource: InteractionSource): DelegatableNode =
    RingNode(interactionSource)

  override fun equals(other: Any?): Boolean = other === this

  override fun hashCode(): Int = 0

  private class RingNode(private val interactionSource: InteractionSource) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private var focused = false
    private var engaged = false

    override fun onAttach() {
      coroutineScope.launch {
        interactionSource.interactions.collect { interaction ->
          when (interaction) {
            is FocusInteraction.Focus -> focused = true
            is FocusInteraction.Unfocus -> {
              focused = false
              engaged = false
            }
            EngagedInteraction.Engage -> engaged = true
            EngagedInteraction.Disengage -> engaged = false
            else -> return@collect
          }
          invalidateDraw()
        }
      }
    }

    override fun ContentDrawScope.draw() {
      drawContent()
      if (!focused) return
      if (currentValueOf(LocalInputModeManager).inputMode != InputMode.Keyboard) return
      val ring = (if (engaged) 4.dp else 2.dp).toPx()
      val halo = ring + 2.dp.toPx()
      val inset = halo / 2
      val topLeft = Offset(inset, inset)
      val ringSize = Size(size.width - halo, size.height - halo)
      drawRect(Color.White.copy(alpha = 0.9f), topLeft, ringSize, style = Stroke(halo))
      drawRect(Color.Black.copy(alpha = 0.8f), topLeft, ringSize, style = Stroke(ring))
    }
  }
}
