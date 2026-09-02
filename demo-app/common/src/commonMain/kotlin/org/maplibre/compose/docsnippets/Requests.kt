@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberDefaultMapRuntime
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestTransform
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceKind
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider

@Composable
fun InterceptorMap(token: String) {
  val runtime = rememberDefaultMapRuntime()
  // #region interceptor
  DisposableEffect(token) {
    runtime.setRequestInterceptor { request ->
      if (request.url.startsWith("https://tiles.example.com/")) {
        MapRequestTransform(headers = mapOf("Authorization" to "Bearer $token"))
      } else {
        MapRequestTransform()
      }
    }
    onDispose { runtime.setRequestInterceptor(null) }
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

@Suppress("UNUSED_PARAMETER") suspend fun readTile(url: String): ByteArray? = null

fun tileProvider(): MapResourceProvider {
  // #region outcomes
  val provider =
    MapResourceProvider(
      accepts = { request -> request.kind == MapResourceKind.Tile },
      load = { request ->
        try {
          when (val bytes = readTile(request.url)) {
            null -> MapResourceLoad.NoContent()
            else -> MapResourceLoad.Bytes(bytes)
          }
        } catch (error: Exception) {
          MapResourceLoad.Failed(MapResourceError.Server, error.message ?: "tile read failed")
        }
      },
    )
  // #endregion outcomes
  return provider
}
