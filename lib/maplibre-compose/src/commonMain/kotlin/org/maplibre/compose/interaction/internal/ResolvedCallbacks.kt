package org.maplibre.compose.interaction.internal

import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.FeatureInteractionRow

internal data class InteractionCallbacks(
  val click: ((ClickEvent) -> ClickResult)? = null,
  val unhandledClick: ((ClickEvent) -> ClickResult)? = null,
  val doubleClick: ((ClickEvent) -> ClickResult)? = null,
  val unhandledDoubleClick: ((ClickEvent) -> ClickResult)? = null,
  val unhandledLongClick: ((ClickEvent) -> ClickResult)? = null,
  val features: List<FeatureInteractionRow> = emptyList(),
  val longClick: ((ClickEvent) -> ClickResult)? = null,
)
