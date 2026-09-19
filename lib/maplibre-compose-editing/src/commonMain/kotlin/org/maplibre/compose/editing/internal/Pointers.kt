package org.maplibre.compose.editing.internal

import org.maplibre.compose.editing.EditorPointer
import org.maplibre.compose.interaction.PointerButton

/** Whether only the secondary mouse button is held. */
internal val EditorPointer.isSecondary: Boolean
  get() = PointerButton.Secondary in buttons && PointerButton.Primary !in buttons

/** Whether the primary button is held, or no button is reported as for touch. */
internal val EditorPointer.isPrimary: Boolean
  get() = PointerButton.Primary in buttons || buttons.isEmpty()
