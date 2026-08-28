package org.maplibre.compose.gljs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapEventClassificationTest {

  @Test
  fun a_bare_error_is_a_style_document_failure() {
    val event = js("{ error: { message: 'failed to parse stylesheet' } }").unsafeCast<MapEvent>()
    assertTrue(event.isStyleDocumentError())
  }

  @Test
  fun a_source_error_is_not_a_style_document_failure() {
    val event =
      js("{ sourceId: 'roads', error: { message: 'tile failed' } }").unsafeCast<MapEvent>()
    assertFalse(event.isStyleDocumentError())
  }

  @Test
  fun a_tile_error_is_not_a_style_document_failure() {
    val event =
      js("{ tile: { tileID: { canonical: { z: 1, x: 0, y: 0 } } }, error: { message: '404' } }")
        .unsafeCast<MapEvent>()
    assertFalse(event.isStyleDocumentError())
  }
}
