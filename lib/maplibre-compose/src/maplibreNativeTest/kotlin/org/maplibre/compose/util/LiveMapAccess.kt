package org.maplibre.compose.util

import kotlin.test.assertNotNull
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.style.StyleBinding
import org.maplibre.nativeffi.map.MapHandle

/** Reads the live map for a loaded-style generation. */
internal fun <T> StyleBinding.onMap(block: (MapHandle) -> T): T =
  assertNotNull(
    (this as? MlnFfiStyleBinding)?.readMap(block),
    "The style is not a loaded MapLibre Native style",
  )
