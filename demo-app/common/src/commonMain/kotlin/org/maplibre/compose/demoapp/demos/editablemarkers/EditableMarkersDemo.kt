package org.maplibre.compose.demoapp.demos.editablemarkers

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.maplibre.compose.demoapp.Demo
import org.maplibre.compose.demoapp.DemoAppState
import org.maplibre.compose.demoapp.DemoDestination
import org.maplibre.compose.demoapp.DemoStyle
import org.maplibre.compose.demoapp.flyTo
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.map.MapState
import org.maplibre.compose.overlay.MapOverlayScope

/** Editable places with scalable labels and a geographically anchored Compose editor. */
object EditableMarkersDemo : Demo {
  override val name = "Editable markers"
  override val description = "A little collection of places worth keeping."
  override val destination = DemoDestination.None

  private val markersState = EditableMarkersState()
  private var mapSize by mutableStateOf(IntSize.Zero)

  override fun interactions(mapState: MapState) =
    with(markersState) {
      MapInteractions(
        if (pressedId != null || draggingId != null) MapInteractions.None
        else MapInteractions.Standard
      ) {
        callbacks {
          click {
            onUnhandled { event ->
              // Dismiss first. Creating a new pin takes a separate tap on the empty map.
              if (editingId != null) editingId = null
              else if (draggingId == null) {
                event.position?.let { position ->
                  add(position)
                }
              }
              ClickResult.Consume
            }
          }
        }
      }
    }

  @Composable
  override fun mapModifier(mapState: MapState): Modifier = Modifier.onGloballyPositioned {
    mapSize = it.size
  }

  @Composable
  override fun MapContent(style: DemoStyle) =
    with(markersState) {
      // Override only these layers: the slider represents an absolute accessibility scale.
      CompositionLocalProvider(
        LocalDensity provides Density(LocalDensity.current.density, textScale)
      ) {
        for (marker in markers) key(marker.id) {
          val motion =
            rememberMarkerMotion(
              marker = marker,
              selected = editingId == marker.id,
              hovered = hoveredId == marker.id,
              pressed = pressedId == marker.id,
              dragging = draggingId == marker.id,
              overTrash = overTrash,
              dragTilt = dragTilt,
              onRemoved = { markers.remove(marker) },
            )
          MarkerLayers(marker, motion, style, onSelect = { select(marker) })
        }
      }
    }

  @Composable
  override fun MapOverlayScope.Overlay(state: DemoAppState) {
    MarkerTargets(markersState)
    MarkerTrash(
      visible = markersState.draggingId != null,
      overTrash = markersState.overTrash,
      needsExit = markersState.trashNeedsExit,
      onBoundsChange = { markersState.trashBounds = it },
      modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
    )
    MarkerEditors(markersState, mapSize)
  }

  @Composable
  override fun Panel(state: DemoAppState) =
    with(markersState) {
      val scope = rememberCoroutineScope()
      EditableMarkersPanel(
        markers = markers,
        selectedId = editingId,
        textScale = textScale,
        onTextScaleChange = { textScale = it },
        onSelect = { marker ->
          select(marker)
          scope.launch {
            state.mapState.flyTo(
              DemoDestination.ExactCamera(
                state.mapState.cameraPosition.copy(target = marker.position)
              )
            )
          }
        },
        onRemove = ::remove,
      )
    }
}
