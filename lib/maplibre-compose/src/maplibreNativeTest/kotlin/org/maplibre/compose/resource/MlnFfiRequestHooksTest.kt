package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.maplibre.nativeffi.resource.ResourceKind

class MlnFfiRequestHooksTest {

  @Test
  fun unnamed_native_kinds_keep_their_identity() {
    val kind = ResourceKind(nativeValue = 99).toCommon()
    assertEquals(99, kind.nativeValue)
    assertNull(kind.browserValue)
    assertEquals(kind, ResourceKind(nativeValue = 99).toCommon())
    assertEquals(kind.hashCode(), ResourceKind(nativeValue = 99).toCommon().hashCode())
    assertNotEquals(MapResourceKind.Unknown, kind)
    assertNotEquals(ResourceKind(nativeValue = 100).toCommon(), kind)
  }

  @Test
  fun named_native_kinds_keep_their_common_identity() {
    val kinds =
      mapOf(
        ResourceKind.UNKNOWN to MapResourceKind.Unknown,
        ResourceKind.STYLE to MapResourceKind.Style,
        ResourceKind.SOURCE to MapResourceKind.Source,
        ResourceKind.TILE to MapResourceKind.Tile,
        ResourceKind.GLYPHS to MapResourceKind.Glyphs,
        ResourceKind.SPRITE_JSON to MapResourceKind.SpriteJson,
        ResourceKind.SPRITE_IMAGE to MapResourceKind.SpriteImage,
        ResourceKind.IMAGE to MapResourceKind.Image,
      )
    for ((native, common) in kinds) {
      assertEquals(common, native.toCommon())
      assertEquals(native.nativeValue, common.nativeValue)
    }
  }
}
