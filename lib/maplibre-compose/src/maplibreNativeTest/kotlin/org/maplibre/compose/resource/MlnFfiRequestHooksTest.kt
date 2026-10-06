package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.nativeffi.resource.ResourceKind

class MlnFfiRequestHooksTest {

  @Test
  fun a_kind_takes_the_engine_name() {
    assertEquals(MapResourceKind.SpriteJson, ResourceKind.SPRITE_JSON.toCommon())
    assertEquals(MapResourceKind.Unknown, ResourceKind.UNKNOWN.toCommon())
  }

  @Test
  fun a_kind_from_a_newer_engine_keeps_its_number() {
    assertEquals("99", ResourceKind(nativeValue = 99).toCommon().value)
  }
}
