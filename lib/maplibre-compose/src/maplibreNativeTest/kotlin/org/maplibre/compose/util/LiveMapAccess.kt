package org.maplibre.compose.util

import kotlin.test.assertNotNull
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.nativeffi.map.MapHandle

/** Reads the live map, failing if the style is not loaded. */
internal fun <T> MlnFfiStyleBinding.onMap(block: (MapHandle) -> T): T =
  assertNotNull(readMap(block), "the style is not loaded")
