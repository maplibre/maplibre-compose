package org.maplibre.compose.resource

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import org.maplibre.compose.mlnffi.currentMlnFfiThreadName
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
 * Native asks for the URL and headers in separate callbacks on the same network thread. One slot
 * per thread is overwritten by the next URL callback, so a cache hit that never reaches HTTP cannot
 * leak a transform to a later request. A request the user provider or the packaged-resource loader
 * will handle is not recorded.
 */
internal class NativeRequestTransforms(private val config: MapResourceConfig) {
  private val lock = reentrantLock()
  private val pending = mutableMapOf<String, MapRequestTransform>()

  fun rewrittenUrl(request: MapResourceRequest): String {
    val transform = config.interceptor().transform(request)
    val nextUrl = transform.url ?: request.url
    if (shouldRecord(nextUrl, request.kind)) {
      lock.withLock { pending[currentMlnFfiThreadName()] = transform }
    }
    return transform.url.orEmpty()
  }

  fun headers(request: MapResourceRequest): List<HttpHeader> =
    take(request).headers.map { HttpHeader(it.key, it.value) }

  internal fun take(request: MapResourceRequest): MapRequestTransform {
    val remembered = lock.withLock { pending.remove(currentMlnFfiThreadName()) }
    return remembered ?: config.interceptor().transform(request)
  }

  internal fun pendingCount(): Int = lock.withLock { pending.size }

  private fun shouldRecord(nextUrl: String, kind: MapResourceKind): Boolean {
    val nextRequest = MapResourceRequest(nextUrl, kind)
    if (config.provider?.acceptsOrDeclines(nextRequest) == true) return false
    return isMapLibresToFetch(nextUrl)
  }
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
