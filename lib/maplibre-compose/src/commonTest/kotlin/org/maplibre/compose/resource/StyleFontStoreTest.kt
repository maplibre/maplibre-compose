package org.maplibre.compose.resource

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.FontFile

class StyleFontStoreTest {

  private val file = FontFile(byteArrayOf(1, 2, 3))
  private val other = FontFile(byteArrayOf(4, 5, 6))

  @Test
  fun a_file_stays_served_while_any_owner_holds_it() {
    val store = StyleFontStore()
    val first = Any()
    val second = Any()
    store.hold(first, listOf(file))
    store.hold(second, listOf(FontFile(byteArrayOf(1, 2, 3))))

    store.drop(first)
    assertContentEquals(file.bytes, store.bytes(file.url))

    store.drop(second)
    assertNull(store.bytes(file.url))
  }

  @Test
  fun holding_replaces_the_owners_previous_set() {
    val store = StyleFontStore()
    val owner = Any()
    store.hold(owner, listOf(file))
    store.hold(owner, listOf(other))

    assertNull(store.bytes(file.url))
    assertContentEquals(other.bytes, store.bytes(other.url))
  }

  @Test
  fun the_config_routes_a_font_url_to_the_store_before_the_provider() = runTest {
    val user = MapResourceProvider(accepts = { true }, load = { MapResourceLoad.NoContent() })
    val config = MapResourceConfig(provider = user)
    config.fonts.hold(this, listOf(file))

    val route = config.route(MapResourceRequest(file.url, MapResourceKind.Unknown))
    assertIs<MapResourceRoute.Load>(route)
    assertSame(config.fonts.provider, route.provider)
    val load = route.provider.load(MapResourceLoadRequest(file.url, MapResourceKind.Unknown))
    assertContentEquals(file.bytes, assertIs<MapResourceLoad.Bytes>(load).bytes)

    val unknown =
      config.fonts.provider.load(MapResourceLoadRequest(other.url, MapResourceKind.Unknown))
    assertEquals(MapResourceError.NotFound, assertIs<MapResourceLoad.Failed>(unknown).reason)
    assertSame(
      user,
      assertIs<MapResourceRoute.Load>(
          config.route(MapResourceRequest("https://tiles.test/a", MapResourceKind.Tile))
        )
        .provider,
    )
  }
}
