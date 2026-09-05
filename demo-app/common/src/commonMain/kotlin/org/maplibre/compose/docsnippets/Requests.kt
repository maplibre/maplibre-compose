@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import kotlinx.coroutines.flow.StateFlow
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MapRuntimeOptions
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.MapResourceKind
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider

// #region configuration
fun configureMapRequests(token: StateFlow<String?>) {
  val interceptor =
    MapRequestInterceptor(
      headers = { request ->
        val currentToken = token.value
        if (request.url.startsWith("https://tiles.example.com/") && currentToken != null) {
          mapOf("Authorization" to "Bearer $currentToken")
        } else {
          emptyMap()
        }
      }
    )
  val provider = MapResourceProvider(scheme = "app") { request -> readAsset(request.url) }
  DefaultMapRuntime.configure(
    MapRuntimeOptions(requestInterceptor = interceptor, resourceProvider = provider)
  )
}

// #endregion configuration

@Suppress("UNUSED_PARAMETER") suspend fun readAsset(url: String): ByteArray = ByteArray(0)

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
