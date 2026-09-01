package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.nativeffi.resource.ResourceKind

class MlnFfiRequestHooksTest {

  @Test
  fun every_named_kind_maps_to_the_common_kind() {
    assertEquals(MapResourceKind.Style, ResourceKind.STYLE.toCommon())
    assertEquals(MapResourceKind.Source, ResourceKind.SOURCE.toCommon())
    assertEquals(MapResourceKind.Tile, ResourceKind.TILE.toCommon())
    assertEquals(MapResourceKind.Glyphs, ResourceKind.GLYPHS.toCommon())
    assertEquals(MapResourceKind.SpriteJson, ResourceKind.SPRITE_JSON.toCommon())
    assertEquals(MapResourceKind.SpriteImage, ResourceKind.SPRITE_IMAGE.toCommon())
    assertEquals(MapResourceKind.Image, ResourceKind.IMAGE.toCommon())
    assertEquals(MapResourceKind.Unknown, ResourceKind.UNKNOWN.toCommon())
    assertEquals(MapResourceKind.Unknown, ResourceKind(nativeValue = 99).toCommon())
  }
}
