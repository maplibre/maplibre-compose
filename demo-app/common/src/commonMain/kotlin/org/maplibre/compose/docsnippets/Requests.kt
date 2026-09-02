@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapRuntime
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestTransform
import org.maplibre.compose.resource.MapResourceProvider

@Composable
fun InterceptorMap(token: String) {
  val runtime = rememberMapRuntime()
  // #region interceptor
  LaunchedEffect(token) {
    runtime.setRequestInterceptor { request ->
      if (request.url.startsWith("https://tiles.example.com/")) {
        MapRequestTransform(headers = mapOf("Authorization" to "Bearer $token"))
      } else {
        MapRequestTransform()
      }
    }
  }
  MaplibreMap(state = rememberMapState(runtime))
  // #endregion interceptor
}

@Suppress("UNUSED_PARAMETER") suspend fun readAsset(url: String): ByteArray = ByteArray(0)

fun assetProvider(): MapResourceProvider {
  // #region provider
  val provider = MapResourceProvider(scheme = "app") { request -> readAsset(request.url) }
  // #endregion provider
  return provider
}
