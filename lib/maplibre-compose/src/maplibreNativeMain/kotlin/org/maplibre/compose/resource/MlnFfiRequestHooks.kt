package org.maplibre.compose.resource

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
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
  } catch (_: Throwable) {
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
 * Native asks for the URL and headers in separate callbacks and identifies a request by URL and
 * kind. Two in-flight requests that share that pair can overwrite the pending result; the displaced
 * header callback calls the interceptor again.
 */
internal class NativeRequestTransforms(private val config: MapResourceConfig) {
  private val lock = reentrantLock()
  private val pending = mutableMapOf<RequestKey, PendingTransform>()

  fun rewrittenUrl(request: MapResourceRequest): String {
    val transform = config.interceptor().transform(request)
    remember(request, transform)
    return transform.url.orEmpty()
  }

  fun headers(request: MapResourceRequest): List<HttpHeader> =
    take(request).headers.map { HttpHeader(it.key, it.value) }

  internal fun take(request: MapResourceRequest): MapRequestTransform {
    val remembered = lock.withLock {
      val found = pending.remove(RequestKey(request.url, request.kind)) ?: return@withLock null
      found.keys.forEach { pending.remove(it) }
      found.transform
    }
    return remembered ?: config.interceptor().transform(request)
  }

  private fun remember(request: MapResourceRequest, transform: MapRequestTransform) {
    val nextUrl = transform.url ?: request.url
    val keys = buildList {
      add(RequestKey(nextUrl, request.kind))
      if (request.url != nextUrl) add(RequestKey(request.url, request.kind))
    }
    val pendingTransform = PendingTransform(transform, keys)
    lock.withLock { keys.forEach { pending[it] = pendingTransform } }
  }

  private data class RequestKey(val url: String, val kind: MapResourceKind)

  private class PendingTransform(
    val transform: MapRequestTransform,
    val keys: List<RequestKey>,
  )
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
