package org.maplibre.compose.map

import kotlin.test.Test
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle

/** An inline style also throws from the setter; that half is shared, in StyleFailureTest. */
class MlnFfiStyleFailureTest {

  @Test
  fun an_unreachable_style_url_is_reported_rather_than_thrown() {
    val fixture = BridgeMapFixture.create()
    fixture.use {
      it.session.setBaseStyle(BaseStyle.Uri("https://example.invalid/style.json"))

      it.pumpUntil("the load to fail") { it.errors.isNotEmpty() }
    }
  }
}
