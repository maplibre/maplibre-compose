package org.maplibre.compose.util

import kotlin.test.assertNotNull
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.nativeffi.map.MapHandle

/**
 * Reads the live map a descriptor is bound to, failing if it is not bound to one. `readMap` answers
 * null when the style has unloaded, which would silently skip the assertions inside [block].
 *
 * A layer speaks style JSON to whichever backend it is attached to, so reaching the `MapHandle`
 * behind it is a downcast; an unattached layer holds the backend-neutral binding instead and fails
 * the assertion.
 */
internal fun <T> Layer.onMap(block: (MapHandle) -> T): T =
  assertNotNull(
    (binding as? MlnFfiStyleBinding)?.readMap(block),
    "Layer '$id' is not bound to a loaded style",
  )

/** The same for a source. */
internal fun <T> Source.onMap(block: (MapHandle) -> T): T =
  assertNotNull(binding.readMap(block), "Source '$id' is not bound to a loaded style")
