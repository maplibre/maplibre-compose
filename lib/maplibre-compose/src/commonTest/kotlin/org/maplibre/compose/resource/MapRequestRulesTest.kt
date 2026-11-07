package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.maplibre.compose.resource.internal.UrlGlob

class MapRequestRulesTest {

  @Test
  fun a_glob_matches_as_maplibre_native_does() {
    val segment = UrlGlob("https://*.example.com/tiles/*.pbf")
    assertTrue(segment.matches("https://a.example.com/tiles/1.pbf"))
    assertFalse(segment.matches("https://a.example.com/tiles/1/2.pbf"), "* stops at /")
    assertFalse(segment.matches("https://a.example.com/tiles/1.pbf?x=1"), "the whole URL matches")

    val spanning = UrlGlob("https://example.com/**")
    assertTrue(spanning.matches("https://example.com/a/b/c?x=1"))
    assertTrue(spanning.matches("https://example.com/"))
    assertFalse(spanning.matches("https://example.org/a"))

    val single = UrlGlob("https://example.com/?.json")
    assertTrue(single.matches("https://example.com/a.json"))
    assertFalse(single.matches("https://example.com//.json"), "? does not match /")
    assertFalse(single.matches("https://example.com/ab.json"))

    val escaped = UrlGlob("""https://example.com/\*.json""")
    assertTrue(escaped.matches("https://example.com/*.json"))
    assertFalse(escaped.matches("https://example.com/a.json"))

    // ? matches one UTF-8 byte, as MapLibre Native compares bytes.
    assertFalse(UrlGlob("https://example.com/?").matches("https://example.com/ü"))
    assertTrue(UrlGlob("https://example.com/??").matches("https://example.com/ü"))
  }

  @Test
  fun the_first_matching_query_rule_adds_its_encoded_parameters() {
    val rules = MapRequestRules {
      addQueryParameters(
        "https://example.com/**",
        mapOf("skip" to "1"),
        setOf(MapResourceKind.Tile),
      )
      addQueryParameters("https://example.com/**", mapOf("key" to "a b&c", "n" to "ü"))
      addQueryParameters("https://example.com/**", mapOf("later" to "1"))
    }

    assertEquals(
      "https://example.com/style.json?key=a%20b%26c&n=%C3%BC",
      rules.rewriteUrl(request("https://example.com/style.json", MapResourceKind.Style)),
    )
    assertEquals(
      "https://example.com/1.pbf?skip=1",
      rules.rewriteUrl(request("https://example.com/1.pbf", MapResourceKind.Tile)),
    )
    assertNull(rules.rewriteUrl(request("https://other.com/style.json", MapResourceKind.Style)))
  }

  @Test
  fun query_parameters_follow_the_existing_query_and_precede_the_fragment() {
    val rules = MapRequestRules { addQueryParameters("**", mapOf("k" to "v")) }
    fun rewrite(url: String) = rules.rewriteUrl(request(url, MapResourceKind.Style))

    assertEquals("https://e.com/a?k=v", rewrite("https://e.com/a"))
    assertEquals("https://e.com/a?x=1&k=v", rewrite("https://e.com/a?x=1"))
    assertEquals("https://e.com/a?k=v", rewrite("https://e.com/a?"))
    assertEquals("https://e.com/a?x=1&k=v", rewrite("https://e.com/a?x=1&"))
    assertEquals("https://e.com/a?k=1&k=v#top", rewrite("https://e.com/a?k=1#top"))
  }

  @Test
  fun the_first_matching_header_rule_supplies_the_headers() {
    val rules = MapRequestRules {
      addHeaders("https://example.com/**", mapOf("Authorization" to "Bearer t"))
      addHeaders("**", mapOf("X-Other" to "1"))
    }

    assertEquals(
      mapOf("Authorization" to "Bearer t"),
      rules.headers(request("https://example.com/a", MapResourceKind.Tile)),
    )
    assertEquals(
      mapOf("X-Other" to "1"),
      rules.headers(request("https://other.com/a", MapResourceKind.Tile)),
    )
    assertEquals(emptyMap(), MapRequestRules.Empty.headers(request("https://e.com/", null)))
  }

  @Test
  fun editing_rules_appends_after_the_rules_of_from() {
    val base = MapRequestRules { addHeaders("https://a.com/**", mapOf("X-A" to "1")) }
    val edited = MapRequestRules(from = base) { addHeaders("**", mapOf("X-B" to "1")) }

    assertEquals(listOf("https://a.com/**", "**"), edited.headerRules.map { it.urlPattern })
    assertEquals(1, base.headerRules.size)
  }

  @Test
  fun invalid_rules_are_rejected_when_added() {
    assertFailsWith<IllegalArgumentException> {
      MapRequestRules { addQueryParameters("**", mapOf("" to "v")) }
    }
    assertFailsWith<IllegalArgumentException> {
      MapRequestRules { addHeaders("**", mapOf("User-Agent" to "v")) }
    }
    assertFailsWith<IllegalArgumentException> {
      MapRequestRules { addHeaders("**", mapOf("Bad Name" to "v")) }
    }
    assertFailsWith<IllegalArgumentException> {
      MapRequestRules { addHeaders("**", mapOf("X-Value" to "a\nb")) }
    }
    assertFailsWith<IllegalArgumentException> {
      MapRequestRules { addHeaders("a".repeat(UrlGlob.MaxTokens + 1), mapOf("X-A" to "1")) }
    }
    assertFailsWith<IllegalArgumentException> {
      MapRequestRules { addHeaders("**", mapOf("X-A" to "1"), setOf(MapResourceKind("8"))) }
    }
  }

  @Test
  fun to_string_omits_the_values() {
    val rules = MapRequestRules { addHeaders("**", mapOf("Authorization" to "Bearer secret")) }

    assertFalse("secret" in rules.toString(), rules.toString())
    assertTrue("Authorization" in rules.toString(), rules.toString())
  }

  private fun request(url: String, kind: MapResourceKind?) =
    MapResourceRequest(url, kind ?: MapResourceKind.Unknown)
}
