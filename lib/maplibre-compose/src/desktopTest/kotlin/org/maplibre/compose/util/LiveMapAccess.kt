package org.maplibre.compose.util

import kotlin.test.assertNotNull
import org.maplibre.compose.layers.Layer
import org.maplibre.compose.sources.Source
import org.maplibre.nativeffi.map.MapHandle

/**
 * Reads the live map a descriptor is bound to, failing if it is not bound to one.
 *
 * `StyleBinding.withMap` answers null rather than throwing when the style has unloaded, which is
 * the right shape for library code and the wrong one for a test: assertions written inside the
 * block simply never run, and the test passes having checked nothing. This makes that case a
 * failure instead.
 */
internal fun <T> Layer.onMap(block: (MapHandle) -> T): T =
  assertNotNull(binding.withMap(block), "Layer '$id' is not bound to a loaded style")

/** The same for a source, which has the same binding and the same silent-null behaviour. */
internal fun <T> Source.onMap(block: (MapHandle) -> T): T =
  assertNotNull(binding.withMap(block), "Source '$id' is not bound to a loaded style")
