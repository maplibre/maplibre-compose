package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.mlnffi.FfiTestPlatform
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceKind
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.resource.MapResourceRequest
import org.maplibre.compose.resource.MapResourceRoute
import org.maplibre.compose.resource.headersOrNone
import org.maplibre.compose.resource.route

class MapRuntimeRequestConfigurationTest {
  @Test
  fun runtimes_keep_their_hooks_while_credentials_refresh() = runTest {
    val token = MutableStateFlow("first")
    val cacheFiles = List(2) { FfiTestPlatform.createCacheFile() }
    val runtimes = mutableListOf<RuntimeImplementation>()
    val providers = List(2) { MapResourceProvider("app") { ByteArray(0) } }
    try {
      repeat(2) { index ->
        runtimes +=
          createMapRuntime(
            MapRuntimeOptions(
              cacheFile = cacheFiles[index],
              requestInterceptor =
                MapRequestInterceptor(
                  rewriteUrl = { "app://runtime-$index/style.json" },
                  headers = { mapOf("Authorization" to if (index == 0) token.value else "other") },
                ),
              resourceProvider = providers[index],
            )
          )
            as RuntimeImplementation
      }
      val request = MapResourceRequest("custom://style.json", MapResourceKind.Style)
      runtimes.forEachIndexed { index, runtime ->
        assertEquals(
          MapResourceRoute.Load(
            request.copy(url = "app://runtime-$index/style.json"),
            providers[index],
          ),
          runtime.resourceConfig.route(request),
        )
      }
      fun headers(index: Int) =
        runtimes[index].resourceConfig.interceptor.headersOrNone(request, null)
      assertEquals(mapOf("Authorization" to "first"), headers(0))
      assertEquals(mapOf("Authorization" to "other"), headers(1))
      token.value = "refreshed"
      assertEquals(mapOf("Authorization" to "refreshed"), headers(0))
      runtimes[0].close()
      runtimes[0].awaitClosed()
      assertEquals(mapOf("Authorization" to "other"), headers(1))
      assertEquals(
        MapResourceRoute.Load(request.copy(url = "app://runtime-1/style.json"), providers[1]),
        runtimes[1].resourceConfig.route(request),
      )
    } finally {
      runtimes.forEach { it.close() }
      runtimes.forEach { it.awaitClosed() }
      cacheFiles.forEach { FfiTestPlatform.deleteCacheFile(it) }
    }
  }
}
