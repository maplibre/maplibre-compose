package org.maplibre.compose.util

import kotlin.test.assertNotNull
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.compose.sources.ffiBinding
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.nativeffi.map.MapHandle

/** Reads the live map a descriptor is bound to, failing if it is not bound to one. */
internal fun <T> Layer.onMap(block: (MapHandle) -> T): T =
  assertNotNull(
    (binding as? MlnFfiStyleBinding)?.readMap(block),
    "Layer '$id' is not bound to a loaded style",
  )

/** The same for a source. */
internal fun <T> Source.onMap(block: (MapHandle) -> T): T =
  assertNotNull(ffiBinding?.readMap(block), "Source '$id' is not bound to a loaded style")
