package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.maplibre.nativeffi.HttpHeader
import org.maplibre.nativeffi.HttpHeaderRule
import org.maplibre.nativeffi.ResourceKind
import org.maplibre.nativeffi.ResourceKindFlag
import org.maplibre.nativeffi.ResourceTransformRule
import org.maplibre.nativeffi.UrlMatchFlag
import org.maplibre.nativeffi.UrlQueryParameter

class MlnFfiRequestHooksTest {

  @Test
  fun a_kind_takes_the_engine_name() {
    assertEquals(MapResourceKind.SpriteJson, ResourceKind.SPRITE_JSON.toCommon())
    assertEquals(MapResourceKind.Unknown, ResourceKind.UNKNOWN.toCommon())
  }

  @Test
  fun a_kind_from_a_newer_engine_keeps_its_number() {
    assertEquals("99", ResourceKind(rawValue = 99u).toCommon().value)
  }

  @Test
  fun request_rules_become_native_rules_that_need_no_callback() {
    val rules = MapRequestRules {
      addQueryParameters(
        "https://tiles.example.com/**",
        mapOf("key" to "k"),
        setOf(MapResourceKind.Tile, MapResourceKind.SpriteJson),
      )
      addHeaders("https://tiles.example.com/*", mapOf("Authorization" to "Bearer t"))
    }

    val transform = assertNotNull(rules.toResourceTransform())
    assertNull(transform.callback)
    assertEquals(
      listOf(
        ResourceTransformRule(
          kinds = ResourceKindFlag.TILE or ResourceKindFlag.SPRITE_JSON,
          flags = UrlMatchFlag.GLOB,
          url = "https://tiles.example.com/**",
          parameters = listOf(UrlQueryParameter("key", "k")),
        ),
      ),
      transform.rules,
    )
    val headers = assertNotNull(rules.toHttpHeaderTransform())
    assertNull(headers.callback)
    assertEquals(
      listOf(
        HttpHeaderRule(
          kinds = ResourceKindFlag.NONE,
          flags = UrlMatchFlag.GLOB,
          url = "https://tiles.example.com/*",
          headers = listOf(HttpHeader("Authorization", "Bearer t")),
        ),
      ),
      headers.rules,
    )
  }

  @Test
  fun an_empty_rule_list_installs_no_transform() {
    assertNull(MapRequestRules.Empty.toResourceTransform())
    assertNull(MapRequestRules.Empty.toHttpHeaderTransform())
  }
}
