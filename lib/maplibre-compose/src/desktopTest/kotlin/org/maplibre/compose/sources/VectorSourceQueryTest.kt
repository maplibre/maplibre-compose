package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.dsl.const

/**
 * Guards the unattached path of [VectorSource.querySourceFeatures].
 *
 * Source feature queries answer from what a render pass built, so a source that has never been
 * added to a style has nothing to answer from. That has to be an empty result rather than a throw:
 * a query racing style load is ordinary, not a caller error.
 *
 * The populated path is deliberately not covered here — it needs a real vector tileset, and a test
 * that reaches the network is worse than no test. It is verified through the demo app instead.
 */
class VectorSourceQueryTest {

  @Test
  fun `querying an unattached source returns empty rather than throwing`() {
    val source = VectorSource(id = "unattached", uri = "https://example.invalid/tiles.json")
    assertTrue(
      source.querySourceFeatures(sourceLayerIds = emptySet(), predicate = const(true)).isEmpty()
    )
  }
}
