package org.maplibre.compose.resource

import androidx.compose.ui.test.ExperimentalTestApi
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.gljs.runBrowserMapTest
import org.maplibre.compose.gljs.setBrowserMapContent
import org.maplibre.compose.gljs.waitUntilMap
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.StyleLoadState
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalTestApi::class)
class BrowserResourceProviderTest {
  @Test
  fun an_engine_style_request_reaches_the_provider_with_its_classifications(): Promise<*> =
    runBrowserMapTest {
      val intercepted = mutableListOf<MapResourceKind>()
      val accepted = mutableListOf<MapResourceKind>()
      val loaded = mutableListOf<MapResourceLoadRequest>()
      val runtime =
        createMapRuntime(
          MapRuntimeOptions(
            requestInterceptor =
              MapRequestInterceptor(
                rewriteUrl = {
                  intercepted += it.kind
                  "app://style.json"
                }
              ),
            resourceProvider =
              MapResourceProvider(
                accepts = {
                  accepted += it.kind
                  it.url.startsWith("app:")
                },
                load = {
                  loaded += it
                  MapResourceLoad.Bytes(
                    """{"version":8,"sources":{},"layers":[]}""".encodeToByteArray()
                  )
                },
              ),
          )
        )
      try {
        val state = runtime.createMapState(BaseStyle.Uri("custom://style.json"))
        setBrowserMapContent { MaplibreMap(state = state) }
        waitUntilMap("the provider style to load") { state.style.loadState == StyleLoadState.Ready }
        assertEquals(listOf(MapResourceKind.Style), intercepted)
        assertEquals(listOf(MapResourceKind.Style), accepted)
        val request = loaded.single()
        assertEquals(MapResourceKind.Style, request.kind)
        assertEquals("app://style.json", request.url)
        assertEquals(MapResourceLoadRequest.LoadingMethod.All, request.loadingMethod)
        assertEquals(MapResourceLoadRequest.Priority.Regular, request.priority)
        assertEquals(MapResourceLoadRequest.Usage.Online, request.usage)
        assertEquals(MapResourceLoadRequest.StoragePolicy.Permanent, request.storagePolicy)
      } finally {
        runtime.close()
        runtime.awaitClosed()
      }
    }
}
