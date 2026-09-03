package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.nativeffi.resource.ResourceKind

class MlnFfiRequestHooksTest {

  @Test
  fun a_kind_from_a_newer_engine_becomes_unknown() {
    assertEquals(MapResourceKind.Unknown, ResourceKind(nativeValue = 99).toCommon())
  }
}
