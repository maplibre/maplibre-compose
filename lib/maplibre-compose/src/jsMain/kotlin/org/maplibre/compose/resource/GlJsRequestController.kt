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
  private val token: String by lazy { newProtocolToken() }

  /** The protocol that loads requests through the [MapResourceProvider]. */
  val scheme: String by lazy { "mlc-res-$token" }

  /** The protocol that fails a request whose [MapRequestInterceptor] threw. */
  val failureScheme: String by lazy { "mlc-failed-$token" }

  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-compose-js-resource")
    )
  private val protocolInstalled = config.provider != null
  private val failureProtocolInstalled = config.interceptor != null
  private var open = true

  init {
    if (protocolInstalled) {
      addProtocol(scheme) { request, abortController -> loadProtocol(request, abortController) }
    }
    if (failureProtocolInstalled) {
      addProtocol(failureScheme) { request, _ -> failProtocol(request) }
    }
  }

  fun transformRequest(url: String, resourceType: String?): Any? {
    val kind = resourceType.toResourceKind()
    val interceptor = config.interceptor
    return when (val route = config.route(MapResourceRequest(url, kind))) {
      is MapResourceRoute.Load ->
        requestParameters(protocolUrl(route.request.url, kind), emptyMap())
      is MapResourceRoute.Fail -> requestParameters(failureUrl(route.request.url, kind), emptyMap())
      is MapResourceRoute.Fetch -> {
        val headers =
          interceptor.headersOrNull(route.request, config.logger)
            ?: return requestParameters(failureUrl(route.request.url, kind), emptyMap())
        if (route.request.url == url && headers.isEmpty()) return undefined
        requestParameters(route.request.url, headers)
      }
    }
  }

  /**
   * Rejects a [failureUrl] request without sending it. The interceptor's exception is already
   * logged.
   *
   * Rejects with a plain JS error: MapLibre copies a tile error through its worker boundary, and a
   * Kotlin exception's non-enumerable `message` does not survive that copy, while a JS error's
   * does.
   */
  internal fun failProtocol(request: RequestParameters): Promise<ProtocolResponse> {
    val url = parseProtocolUrl(request.url, failureScheme).url
    val error = js("new Error()")
    error.message = "The request interceptor failed for $url, so the request was not sent"
    return Promise.reject(error.unsafeCast<Throwable>())
  }

  internal fun loadProtocol(
    request: RequestParameters,
    abortController: Any,
  ): Promise<ProtocolResponse> {
    val parsed = parseProtocolUrl(request.url, scheme)
    val work = scope.async {
      val provider =
        config.provider ?: throw IllegalStateException("No resource provider is installed")
      // MapLibre GL JS passes only the URL and the kind, so every other field is the default.
      val result = provider.load(MapResourceLoadRequest(parsed.url, parsed.kind))
      result.toProtocolResponse(parsed.url)
    }
    val signal = abortController.asDynamic().signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion { signal.removeEventListener("abort", abort) }
    if (signal.aborted == true) work.cancel()
    return work.asPromise()
  }

  fun protocolUrl(url: String, kind: MapResourceKind): String = protocolUrl(scheme, url, kind)

  /** A URL in [failureScheme] that names the request whose interceptor threw. */
  fun failureUrl(url: String, kind: MapResourceKind): String = protocolUrl(failureScheme, url, kind)

  private fun protocolUrl(scheme: String, url: String, kind: MapResourceKind): String =
    "$scheme://${encodeResourceUrl(kind.value)}/${encodeResourceUrl(url)}"

  fun parseProtocolUrl(protocolUrl: String, scheme: String = this.scheme): MapResourceRequest {
    val prefix = "$scheme://"
    require(protocolUrl.startsWith(prefix)) { "Invalid resource protocol URL: $protocolUrl" }
    val remainder = protocolUrl.substring(prefix.length)
    val separator = remainder.indexOf('/')
    require(separator > 0) { "Invalid resource protocol URL: $protocolUrl" }
    val kind = remainder.substring(0, separator).toStoredResourceKind()
    return MapResourceRequest(decodeResourceUrl(remainder.substring(separator + 1)), kind)
  }

  override fun close() {
    if (!open) return
    open = false
    scope.cancel()
    if (protocolInstalled) removeProtocol(scheme)
    if (failureProtocolInstalled) removeProtocol(failureScheme)
  }
}

/**
 * A per-runtime token for protocol schemes that another map on the page cannot guess.
 *
 * Uses `crypto.getRandomValues`, which is present in non-secure HTTP contexts where
 * `crypto.randomUUID` is not.
 */
private fun newProtocolToken(): String {
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
  return token
}

private val undefined: Any? = js("undefined")

/** The kind that MapLibre GL JS names with this resource type, or [MapResourceKind.Unknown]. */
internal fun String?.toResourceKind(): MapResourceKind =
  if (isNullOrEmpty()) MapResourceKind.Unknown else MapResourceKind(this)

/** Parses a kind that [GlJsRequestController.protocolUrl] stored. */
internal fun String.toStoredResourceKind(): MapResourceKind =
  MapResourceKind(decodeResourceUrl(this))

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
    else -> null
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
private fun MapResourceLoad.toProtocolResponse(url: String): ProtocolResponse {
  val expires = expires?.let { Date(it.toEpochMilliseconds().toDouble()) }
  return when (this) {
    is MapResourceLoad.Bytes -> bytes.toProtocolResponse(expires)
    is MapResourceLoad.NoContent -> ByteArray(0).toProtocolResponse(expires)
    is MapResourceLoad.NotModified ->
      throw ResourceLoadError(
        "Resource provider returned NotModified for $url, but the browser sends no validators",
        status = null,
      )
    is MapResourceLoad.Failed -> throw ResourceLoadError(message, reason.httpStatus())
  }
}

private fun ByteArray.toProtocolResponse(expires: Date?): ProtocolResponse {
  val bytes = Uint8Array<ArrayBuffer>(size)
  forEachIndexed { index, byte -> bytes.asDynamic()[index] = byte.toInt() and 0xFF }
  return unsafeJso {
    data = bytes.buffer
    if (expires != null) this.expires = expires
  }
}
