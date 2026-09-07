package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult

internal data class InteractionCallbacks(
  val click: ((ClickEvent) -> ClickResult)? = null,
  val unhandledClick: ((ClickEvent) -> ClickResult)? = null,
  val doubleClick: ((ClickEvent) -> ClickResult)? = null,
  val longClick: ((ClickEvent) -> ClickResult)? = null,
)
