package org.maplibre.compose.sources

import kotlin.test.Test
import kotlin.test.assertTrue
import org.maplibre.compose.expressions.dsl.const

/**
 * Guards the unattached path of [VectorSource.querySourceFeatures]: a query racing style load is
 * ordinary, so it must be empty rather than a throw. The populated path needs a real tileset and is
 * covered through the demo app.
 */
class VectorSourceQueryTest {

  @Test
  fun querying_an_unattached_source_returns_empty_rather_than_throwing() {
    val source = VectorSource(id = "unattached", uri = "https://example.invalid/tiles.json")
    assertTrue(
      source.querySourceFeatures(sourceLayerIds = emptySet(), predicate = const(true)).isEmpty()
    )
  }
}
