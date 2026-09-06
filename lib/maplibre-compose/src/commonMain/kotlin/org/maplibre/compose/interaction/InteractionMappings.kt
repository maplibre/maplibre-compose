package org.maplibre.compose.interaction

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerType
import org.maplibre.compose.interaction.internal.DragMapping
import org.maplibre.compose.interaction.internal.DragResponse
import org.maplibre.compose.interaction.internal.KeyMapping
import org.maplibre.compose.interaction.internal.KeyResponse
import org.maplibre.compose.interaction.internal.PointerPattern
import org.maplibre.compose.interaction.internal.ScrollMapping
import org.maplibre.compose.interaction.internal.ScrollResponse
import org.maplibre.compose.interaction.internal.TapMapping
import org.maplibre.compose.interaction.internal.TapResponse
import org.maplibre.compose.interaction.internal.select

/** One response is required in every mapping row. */
@MapInteractionDsl
public class DragResponseBuilder internal constructor() {
  private var response: DragResponse? = null

  private fun select(value: DragResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun pan() {
    select(DragResponse.Pan)
  }

  public fun rotateTilt() {
    select(DragResponse.RotateTilt)
  }

  public fun fitBounds() {
    select(DragResponse.FitBounds)
  }

  public fun none() {
    select(DragResponse.None)
  }

  internal fun build(): DragResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

@MapInteractionDsl
public class ScrollResponseBuilder internal constructor() {
  private var response: ScrollResponse? = null

  private fun select(value: ScrollResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun pan() {
    select(ScrollResponse.Pan)
  }

  /** Zooms from vertical scrolling. Horizontal-only events remain unclaimed. */
  public fun zoom() {
    select(ScrollResponse.Zoom)
  }

  public fun none() {
    select(ScrollResponse.None)
  }

  internal fun build(): ScrollResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

@MapInteractionDsl
public class TapResponseBuilder internal constructor() {
  private var response: TapResponse? = null

  private fun select(value: TapResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun zoomIn() {
    select(TapResponse.ZoomIn)
  }

  public fun zoomOut() {
    select(TapResponse.ZoomOut)
  }

  public fun none() {
    select(TapResponse.None)
  }

  internal fun build(): TapResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

@MapInteractionDsl
public class KeyResponseBuilder internal constructor() {
  private var response: KeyResponse? = null

  private fun select(value: KeyResponse) {
    require(response == null) { "A mapping must declare exactly one response" }
    response = value
  }

  public fun panLeft() {
    select(KeyResponse.PanLeft)
  }

  public fun panRight() {
    select(KeyResponse.PanRight)
  }

  public fun panUp() {
    select(KeyResponse.PanUp)
  }

  public fun panDown() {
    select(KeyResponse.PanDown)
  }

  public fun zoomIn() {
    select(KeyResponse.ZoomIn)
  }

  public fun zoomOut() {
    select(KeyResponse.ZoomOut)
  }

  public fun rotateLeft() {
    select(KeyResponse.RotateLeft)
  }

  public fun rotateRight() {
    select(KeyResponse.RotateRight)
  }

  public fun tiltUp() {
    select(KeyResponse.TiltUp)
  }

  public fun tiltDown() {
    select(KeyResponse.TiltDown)
  }

  public fun engage() {
    select(KeyResponse.Engage)
  }

  public fun disengage() {
    select(KeyResponse.Disengage)
  }

  public fun back() {
    select(KeyResponse.Back)
  }

  public fun none() {
    select(KeyResponse.None)
  }

  internal fun build(): KeyResponse =
    requireNotNull(response) { "A mapping must declare a response" }
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class DragMappingsBuilder internal constructor() {
  private val rows = mutableListOf<DragMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    button: PointerButton? = null,
    modifiers: ModifierMatch = ModifierMatch.Any,
    block: DragResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      DragMapping(
        PointerPattern(pointerTypes?.toSet(), button, modifiers),
        DragResponseBuilder().apply(block).build(),
      )
  }

  public fun otherwise(block: DragResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += DragMapping(PointerPattern(), DragResponseBuilder().apply(block).build())
  }

  internal fun build(): List<DragMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class ScrollMappingsBuilder internal constructor() {
  private val rows = mutableListOf<ScrollMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    modifiers: ModifierMatch = ModifierMatch.Any,
    block: ScrollResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      ScrollMapping(
        PointerPattern(pointerTypes?.toSet(), modifiers = modifiers),
        ScrollResponseBuilder().apply(block).build(),
      )
  }

  public fun otherwise(block: ScrollResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += ScrollMapping(PointerPattern(), ScrollResponseBuilder().apply(block).build())
  }

  internal fun build(): List<ScrollMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class TapMappingsBuilder internal constructor() {
  private val rows = mutableListOf<TapMapping>()
  private var hasOtherwise = false

  public fun on(
    pointerTypes: Set<PointerType>? = null,
    button: PointerButton? = null,
    modifiers: ModifierMatch = ModifierMatch.Any,
    block: TapResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows +=
      TapMapping(
        PointerPattern(pointerTypes?.toSet(), button, modifiers),
        TapResponseBuilder().apply(block).build(),
      )
  }

  public fun otherwise(block: TapResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += TapMapping(PointerPattern(), TapResponseBuilder().apply(block).build())
  }

  internal fun build(): List<TapMapping> = rows.toList()
}

/** Ordered mappings; the first matching permitted response wins. */
@MapInteractionDsl
public class KeyMappingsBuilder internal constructor() {
  private val rows = mutableListOf<KeyMapping>()
  private var hasOtherwise = false

  /** Modifiers match exactly by default, so unconfigured system shortcuts remain unclaimed. */
  public fun on(
    key: Key,
    modifiers: ModifierMatch = ModifierMatch.Exactly(),
    block: KeyResponseBuilder.() -> Unit,
  ) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    rows += KeyMapping(key, modifiers, KeyResponseBuilder().apply(block).build())
  }

  public fun otherwise(block: KeyResponseBuilder.() -> Unit) {
    require(!hasOtherwise) { "otherwise must be the final row" }
    hasOtherwise = true
    rows += KeyMapping(null, ModifierMatch.Any, KeyResponseBuilder().apply(block).build())
  }

  internal fun build(): List<KeyMapping> = rows.toList()
}
