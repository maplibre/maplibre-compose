package org.maplibre.compose.resource

import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.js.Date
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.RequestParameters
import org.maplibre.compose.gljs.addProtocol
import org.maplibre.compose.gljs.removeProtocol

internal class GlJsRequestController(private val config: MapResourceConfig) : AutoCloseable {
  val scheme: String by lazy { newResourceProtocolScheme() }
  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-compose-js-resource")
    )
  private val protocolInstalled = config.provider != null
  private var open = true

  init {
    if (protocolInstalled) {
      addProtocol(scheme) { request, abortController -> loadProtocol(request, abortController) }
    }
  }

  fun transformRequest(url: String, resourceType: String?): Any? {
    val kind = resourceType.toResourceKind()
    val interceptor = config.interceptor
    return when (val route = config.route(MapResourceRequest(url, kind))) {
      is MapResourceRoute.Load ->
        requestParameters(protocolUrl(route.request.url, kind), emptyMap())
      is MapResourceRoute.Fetch -> {
        val headers = interceptor.headersOrNone(route.request, config.logger)
        if (route.request.url == url && headers.isEmpty()) return undefined
        requestParameters(route.request.url, headers)
      }
    }
  }

  internal fun loadProtocol(
    request: RequestParameters,
    abortController: Any,
  ): Promise<ProtocolResponse> {
    val parsed = parseProtocolUrl(request.url)
    val work = scope.async {
      val provider =
        config.provider ?: throw IllegalStateException("No resource provider is installed")
      // MapLibre GL JS passes only the URL and the kind, so every other field is the default.
      val result = provider.load(MapResourceLoadRequest(parsed.url, parsed.kind))
      result.toProtocolResponse(parsed.url, request.type)
    }
    val signal = abortController.asDynamic().signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion { signal.removeEventListener("abort", abort) }
    if (signal.aborted == true) work.cancel()
    return work.asPromise()
  }

  // The prefix keeps the kind component nonempty even for an empty engine string.
  fun protocolUrl(url: String, kind: MapResourceKind): String =
    "$scheme://k${encodeResourceUrl(requireNotNull(kind.browserValue))}/${encodeResourceUrl(url)}"

  fun parseProtocolUrl(protocolUrl: String): MapResourceRequest {
    val prefix = "$scheme://"
    require(protocolUrl.startsWith(prefix)) { "Invalid resource protocol URL: $protocolUrl" }
    val remainder = protocolUrl.substring(prefix.length)
    val separator = remainder.indexOf('/')
    require(separator > 0) { "Invalid resource protocol URL: $protocolUrl" }
    val storedKind = remainder.substring(0, separator)
    require(storedKind.startsWith("k")) { "Invalid resource kind in protocol URL: $protocolUrl" }
    val kind = decodeResourceUrl(storedKind.substring(1)).toResourceKind()
    return MapResourceRequest(decodeResourceUrl(remainder.substring(separator + 1)), kind)
  }

  override fun close() {
    if (!open) return
    open = false
    scope.cancel()
    if (protocolInstalled) removeProtocol(scheme)
  }
}

/**
 * A per-runtime scheme that another map on the page cannot guess.
 *
 * Uses `crypto.getRandomValues`, which is present in non-secure HTTP contexts where
 * `crypto.randomUUID` is not.
 */
private fun newResourceProtocolScheme(): String {
  val bytes = Uint8Array<ArrayBuffer>(16)
  js("crypto.getRandomValues")(bytes)
  val token =
    buildString(32) {
      for (index in 0 until 16) {
        val value = bytes.asDynamic()[index].unsafeCast<Int>()
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0x0f])
      }
    }
  return "mlc-res-$token"
}

private val undefined: Any? = js("undefined")

internal fun String?.toResourceKind(): MapResourceKind =
  when (this) {
    "Style" -> MapResourceKind.Style
    "Source" -> MapResourceKind.Source
    "Tile" -> MapResourceKind.Tile
    "Glyphs" -> MapResourceKind.Glyphs
    "SpriteJSON" -> MapResourceKind.SpriteJson
    "SpriteImage" -> MapResourceKind.SpriteImage
    "Image" -> MapResourceKind.Image
    "Unknown",
    null -> MapResourceKind.Unknown
    else -> MapResourceKind(null, this)
  }

private fun requestParameters(url: String, headers: Map<String, String>): Any {
  val params = js("{}")
  params.url = url
  if (headers.isNotEmpty()) {
    val headerObject = js("{}")
    headers.forEach { (name, value) -> headerObject[name] = value }
    params.headers = headerObject
  }
  return params
}

/**
 * The HTTP status that MapLibre GL JS reads from a rejected protocol promise, or null for a reason
 * with no status. Only 404 changes its behavior: it skips a tile with that status.
 */
internal fun MapResourceError.httpStatus(): Int? =
  when (this) {
    MapResourceError.NotFound -> 404
    MapResourceError.Server -> 500
    MapResourceError.RateLimit -> 429
    MapResourceError.Connection,
    MapResourceError.Other -> null
  }

/**
 * The rejection of a protocol load. [status] is set on the JS object for MapLibre GL JS to read.
 */
internal class ResourceLoadError(message: String, val status: Int?) : Exception(message) {
  init {
    if (status != null) asDynamic().status = status
  }
}

/** Converts a load result to the protocol promise outcome of the corresponding HTTP response. */
private fun MapResourceLoad.toProtocolResponse(url: String, type: String?): ProtocolResponse {
  val expires = expires?.let { Date(it.toEpochMilliseconds().toDouble()) }
  return when (this) {
    is MapResourceLoad.Bytes -> bytes.toProtocolResponse(expires, type)
    is MapResourceLoad.NoContent -> ByteArray(0).toProtocolResponse(expires, type)
    is MapResourceLoad.NotModified ->
      throw ResourceLoadError(
        "Resource provider returned NotModified for $url, but the browser sends no validators",
        status = null,
      )
    is MapResourceLoad.Failed -> throw ResourceLoadError(message, reason.httpStatus())
  }
}

private fun ByteArray.toProtocolResponse(expires: Date?, type: String?): ProtocolResponse {
  // GL JS protocol handlers return decoded JSON for getJSON, and buffers for binary loads.
  val data =
    if (type == "json") JSON.parse<Any>(decodeToString())
    else {
      val bytes = Uint8Array<ArrayBuffer>(size)
      forEachIndexed { index, byte -> bytes.asDynamic()[index] = byte.toInt() and 0xFF }
      bytes.buffer
    }
  return unsafeJso {
    this.data = data
    if (expires != null) this.expires = expires
  }
}
