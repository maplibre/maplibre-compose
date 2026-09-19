package org.maplibre.compose.editing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.maplibre.compose.editing.internal.latitudeFromMercatorY
import org.maplibre.compose.editing.internal.longitudeFromMercatorX
import org.maplibre.compose.editing.internal.mercatorX
import org.maplibre.compose.editing.internal.mercatorY
import org.maplibre.compose.interaction.KeyModifier
import org.maplibre.compose.interaction.PointerButton
import org.maplibre.spatialk.geojson.Position

/** Builds tool events over one projection pair, as the modifier would deliver them. */
internal class Events(
  val project: (Position) -> DpOffset?,
  val unproject: (DpOffset) -> Position?,
) {
  fun pointer(
    position: Position,
    type: PointerType = PointerType.Mouse,
    buttons: Set<PointerButton> =
      if (type == PointerType.Touch) emptySet() else setOf(PointerButton.Primary),
    modifiers: Set<KeyModifier> = emptySet(),
  ): EditorPointer = EditorPointer(project(position)!!, position, type, buttons, modifiers)

  fun hitAt(state: FeatureEditorState, position: Position, radius: Dp = 2.dp): EditorHit? =
    state.hitTest(project(position)!!, radius, unproject).firstOrNull()

  fun press(pointer: EditorPointer, hit: EditorHit?, step: EditStep = EditStep()) =
    EditorEvent.Press(pointer, hit, step, project, unproject)

  fun drag(
    pointer: EditorPointer,
    origin: EditorPointer,
    hit: EditorHit?,
    step: EditStep,
    previous: EditorPointer = origin,
  ) = EditorEvent.Drag(pointer, origin, previous, hit, step, project, unproject)

  fun release(pointer: EditorPointer, step: EditStep) =
    EditorEvent.Release(pointer, step, project, unproject)

  fun tap(pointer: EditorPointer, hit: EditorHit?, count: Int = 1, step: EditStep = EditStep()) =
    EditorEvent.Tap(pointer, hit, count, step, project, unproject)

  fun cancel(step: EditStep) = EditorEvent.Cancel(step, project, unproject)

  fun hover(pointer: EditorPointer, hit: EditorHit?) =
    EditorEvent.Hover(pointer, hit, project, unproject)

  fun key(
    key: Key,
    type: KeyEventType = KeyEventType.KeyDown,
    modifiers: Set<KeyModifier> = emptySet(),
  ) = EditorEvent.Key(key, type, modifiers, project, unproject)

  companion object {
    /** The flat fixture map: one dp is a tenth of a degree. */
    val flat = Events(project = { screenOf(it.longitude, it.latitude) }, unproject = ::unproject)

    /** Web Mercator with the world [worldDp] wide. */
    fun mercator(worldDp: Double = 3600.0) =
      Events(
        project = {
          DpOffset((mercatorX(it.longitude) * worldDp).dp, (mercatorY(it.latitude) * worldDp).dp)
        },
        unproject = {
          Position(
            longitudeFromMercatorX(it.x.value / worldDp),
            latitudeFromMercatorY(it.y.value / worldDp),
          )
        },
      )
  }
}
