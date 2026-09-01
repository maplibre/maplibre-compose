package org.maplibre.compose.resource

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.resource.HttpHeader
import org.maplibre.nativeffi.resource.HttpHeaderTransformCallback
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceTransformCallback
import org.maplibre.nativeffi.runtime.RuntimeHandle

/** Installs live URL and header transforms that read [config] on every request. */
internal fun RuntimeHandle.installRequestInterceptor(config: MapResourceConfig) {
  val transforms = NativeRequestTransforms(config)
  var headersInstalled = false
  try {
    setHttpHeaderTransform(
      HttpHeaderTransformCallback { request ->
        transforms.headers(MapResourceRequest(request.url, request.kind.toCommon()))
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
      if (headersInstalled) transforms.rewrittenUrl(mapRequest)
      else config.interceptor().transform(mapRequest).url.orEmpty()
    }
  )
}

/**
 * Remembers the URL-callback interceptor result so the header callback can reuse it.
 *
 * Native asks for the URL and headers in separate callbacks. Pending results are queued by the URL
 * the client will send. A request the user provider or the packaged-resource loader will handle
 * never reaches the header callback, so it is not recorded. Header callbacks consume the queue in
 * URL-callback order.
 */
internal class NativeRequestTransforms(private val config: MapResourceConfig) {
  private val lock = reentrantLock()
  private val pending = mutableMapOf<RequestKey, ArrayDeque<MapRequestTransform>>()

  fun rewrittenUrl(request: MapResourceRequest): String {
    val transform = config.interceptor().transform(request)
    val nextUrl = transform.url ?: request.url
    if (shouldRecord(nextUrl, request.kind)) {
      lock.withLock {
        pending.getOrPut(RequestKey(nextUrl, request.kind), ::ArrayDeque).addLast(transform)
      }
    }
    return transform.url.orEmpty()
  }

  fun headers(request: MapResourceRequest): List<HttpHeader> =
    take(request).headers.map { HttpHeader(it.key, it.value) }

  internal fun take(request: MapResourceRequest): MapRequestTransform {
    val remembered = lock.withLock {
      val key = RequestKey(request.url, request.kind)
      val queue = pending[key] ?: return@withLock null
      val transform = queue.removeFirst()
      if (queue.isEmpty()) pending.remove(key)
      transform
    }
    return remembered ?: config.interceptor().transform(request)
  }

  internal fun pendingCount(): Int = lock.withLock { pending.values.sumOf { it.size } }

  private fun shouldRecord(nextUrl: String, kind: MapResourceKind): Boolean {
    val nextRequest = MapResourceRequest(nextUrl, kind)
    if (config.provider?.acceptsOrDeclines(nextRequest) == true) return false
    return isMapLibresToFetch(nextUrl)
  }

  private data class RequestKey(val url: String, val kind: MapResourceKind)
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
