package org.maplibre.compose.interaction

import androidx.compose.ui.unit.DpOffset
import org.maplibre.compose.interaction.internal.GesturePointerSample

internal fun testClickEvent(offset: DpOffset = DpOffset.Zero): ClickEvent =
  ClickEvent(GesturePointerSample(0, offset, null, emptySet(), emptySet(), emptySet()))
