@file:Suppress("unused")

package org.maplibre.compose.docsnippets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.StateFlow
import org.maplibre.compose.demoapp.generated.Res
import org.maplibre.compose.map.DefaultMapRuntime
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.createMapRuntime
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.resource.MapRequestInterceptor
import org.maplibre.compose.resource.MapResourceKind
import org.maplibre.compose.resource.MapResourceLoad
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle

fun authHeaders(token: StateFlow<String?>): MapRequestInterceptor {
  // #region headers
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
  // #endregion headers
  return interceptor
}

fun apiKeyRewrite(apiKey: String): MapRequestInterceptor {
  // #region rewrite
  val interceptor =
    MapRequestInterceptor(
      rewriteUrl = { request ->
        if (request.url.startsWith("https://tiles.example.com/")) {
          val separator = if ('?' in request.url) '&' else '?'
          "${request.url}${separator}key=$apiKey"
        } else {
          null
        }
      }
    )
  // #endregion rewrite
  return interceptor
}

fun bundledTiles(): MapResourceProvider {
  // #region scheme
  // Serves app://tiles/14/2627/5721.pbf from composeResources/files/tiles/14/2627/5721.pbf
  val provider =
    MapResourceProvider(scheme = "app") { request ->
      Res.readBytes("files/" + request.url.removePrefix("app://"))
    }
  // #endregion scheme
  return provider
}

@Suppress("UNUSED_PARAMETER") suspend fun readTile(url: String): ByteArray? = null

fun databaseTiles(): MapResourceProvider {
  // #region outcomes
  val provider =
    MapResourceProvider(
      accepts = { request ->
        request.kind == MapResourceKind.Tile && request.url.startsWith("app://tiles/")
      },
      load = { request ->
        when (val bytes = readTile(request.url)) {
          null -> MapResourceLoad.NoContent()
          else -> MapResourceLoad.Bytes(bytes)
        }
      },
    )
  // #endregion outcomes
  return provider
}

// #region configuration
fun configureMaps(interceptor: MapRequestInterceptor, provider: MapResourceProvider) {
  DefaultMapRuntime.configure {
    requestInterceptor = interceptor
    resourceProvider = provider
  }
}

// #endregion configuration

@Composable
fun SeparateRuntimeMap(interceptor: MapRequestInterceptor) {
  // #region separate-runtime
  val runtime = remember { createMapRuntime { requestInterceptor = interceptor } }
  DisposableEffect(runtime) { onDispose { runtime.close() } }

  val state =
    rememberMapState(
      runtime = runtime,
      baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"),
    )
  MaplibreMap(state = state)
  // #endregion separate-runtime
}
