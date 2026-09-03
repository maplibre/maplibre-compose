package org.maplibre.compose.resource

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.resource.HttpHeader
import org.maplibre.nativeffi.resource.HttpHeaderTransformCallback
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceTransformCallback
import org.maplibre.nativeffi.runtime.RuntimeHandle

/** Installs the URL and header callbacks for [requests]. */
internal fun RuntimeHandle.installRequestInterceptor(requests: NativeRequestCoordinator) {
  var headersInstalled = false
  try {
    setHttpHeaderTransform(
      HttpHeaderTransformCallback { request ->
        requests.headers(MapResourceRequest(request.url, request.kind.toCommon()))
      }
    )
    headersInstalled = true
  } catch (error: Throwable) {
    rethrowIfFatal(error)
    // OpenHarmony and the browser FFI decline header transforms.
  }
  setResourceTransform(
    ResourceTransformCallback { request ->
      val mapRequest = MapResourceRequest(request.url, request.kind.toCommon())
      requests.rewrittenUrl(mapRequest, rememberHeaders = headersInstalled)
    }
  )
}

/**
 * Routes native requests and carries one interceptor result through native's separate callbacks.
 *
 * The provider callback runs before native's URL callback. Native then asks for the URL and headers
 * in separate callbacks. Each callback takes the oldest transform for its request.
 */
internal class NativeRequestCoordinator(private val config: MapResourceConfig) {
  private val lock = reentrantLock()
  private val pendingEngine = mutableMapOf<MapResourceRequest, ArrayDeque<MapRequestTransform>>()
  private val pendingHeaders = mutableMapOf<MapResourceRequest, ArrayDeque<MapRequestTransform>>()

  fun route(request: ResourceRequest, passThroughNetwork: Boolean): NativeResourceRoute {
    val incoming = MapResourceRequest(request.resolvedUrl, request.kind.toCommon())
    return when (val route = config.route(incoming)) {
      is MapResourceRoute.Load ->
        NativeResourceRoute.Load(
          provider = route.provider,
          request = request.toLoadRequest(url = route.request.url),
        )
      is MapResourceRoute.Fetch -> {
        if (passThroughNetwork && isMapLibresToFetch(route.request.url)) {
          lock.withLock {
            pendingEngine.getOrPut(incoming, ::ArrayDeque).addLast(route.transform)
          }
          NativeResourceRoute.Fetch
        } else {
          NativeResourceRoute.Read(route.request.url)
        }
      }
    }
  }

  fun rewrittenUrl(request: MapResourceRequest, rememberHeaders: Boolean = true): String {
    val transform = checkNotNull(takeEngineTransform(request)) { "No route for $request" }
    val nextUrl = transform.url ?: request.url
    if (rememberHeaders && isMapLibresToFetch(nextUrl)) {
      val transformed = request.copy(url = nextUrl)
      lock.withLock { pendingHeaders.getOrPut(transformed, ::ArrayDeque).addLast(transform) }
    }
    return transform.url.orEmpty()
  }

  fun headers(request: MapResourceRequest): List<HttpHeader> =
    takeHeaderTransform(request).headers.map { HttpHeader(it.key, it.value) }

  private fun takeEngineTransform(request: MapResourceRequest): MapRequestTransform? =
    lock.withLock {
      val queued = pendingEngine[request] ?: return@withLock null
      val transform = queued.removeFirst()
      if (queued.isEmpty()) pendingEngine.remove(request)
      transform
    }

  private fun takeHeaderTransform(request: MapResourceRequest): MapRequestTransform {
    val remembered = lock.withLock {
      val queued = pendingHeaders[request] ?: return@withLock null
      val transform = queued.removeFirst()
      if (queued.isEmpty()) pendingHeaders.remove(request)
      transform
    }
    return remembered ?: config.interceptor().transform(request)
  }

  internal fun pendingEngineCount(): Int = lock.withLock { pendingEngine.values.sumOf { it.size } }

  internal fun pendingHeaderCount(): Int = lock.withLock { pendingHeaders.values.sumOf { it.size } }
}

internal sealed interface NativeResourceRoute {
  data class Load(val provider: MapResourceProvider, val request: MapResourceLoadRequest) :
    NativeResourceRoute

  data object Fetch : NativeResourceRoute

  data class Read(val url: String) : NativeResourceRoute
}

internal fun ResourceKind.toCommon(): MapResourceKind =
  when (this) {
    ResourceKind.STYLE -> MapResourceKind.Style
    ResourceKind.SOURCE -> MapResourceKind.Source
    ResourceKind.TILE -> MapResourceKind.Tile
    ResourceKind.GLYPHS -> MapResourceKind.Glyphs
    ResourceKind.SPRITE_JSON -> MapResourceKind.SpriteJson
    ResourceKind.SPRITE_IMAGE -> MapResourceKind.SpriteImage
    ResourceKind.IMAGE -> MapResourceKind.Image
    else -> MapResourceKind.Unknown
  }
