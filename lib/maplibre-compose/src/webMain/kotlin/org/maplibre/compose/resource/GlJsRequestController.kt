package org.maplibre.compose.resource

import js.buffer.ArrayBuffer
import js.date.Date
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asPromise
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.maplibre.compose.gljs.ProtocolAbortController
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.RequestParameters
import org.maplibre.compose.gljs.addProtocol
import org.maplibre.compose.gljs.removeProtocol
import org.maplibre.compose.gljs.setUint8At

internal class GlJsRequestController(private val config: MapResourceConfig) : AutoCloseable {
  val scheme: String by lazy { newResourceProtocolScheme() }
  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-compose-js-resource"),
    )
  private val protocolInstalled = config.provider != null
  private var open = true

  init {
    if (protocolInstalled) {
      addProtocol(scheme) { request, abortController -> loadProtocol(request, abortController) }
    }
  }

  fun transformRequest(url: String, resourceType: String?): JsAny? {
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
    abortController: ProtocolAbortController,
  ): Promise<ProtocolResponse> {
    val parsed = parseProtocolUrl(request.url)
    val work =
      scope.async<JsAny> {
        try {
          val provider =
            config.provider ?: throw IllegalStateException("No resource provider is installed")
          // MapLibre GL JS passes only the URL and the kind, so every other field is the default.
          val result =
            try {
              provider.load(MapResourceLoadRequest(parsed.url, parsed.kind))
            } catch (error: Throwable) {
              // A cancelled job means the request ended. The provider's own cancellation, such as a
              // timeout, leaves the job active and fails like any other exception.
              if (error is CancellationException) currentCoroutineContext().ensureActive()
              throw resourceLoadError(error.message ?: error.toString(), status = null, parsed.url)
            }
          val workerTile = parsed.kind == MapResourceKind.Tile && request.type == "arrayBuffer"
          result.toProtocolResponse(parsed.url, workerTile)
        } catch (error: Throwable) {
          if (error is CancellationException) currentCoroutineContext().ensureActive()
          protocolError(
            error.message ?: error.toString(),
            (error as? ResourceLoadFailure)?.status,
            parsed.url,
          )
        }
      }
    val signal = abortController.signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion { signal.removeEventListener("abort", abort) }
    if (signal.aborted == true) work.cancel()
    return unwrapProtocolResult(work.asPromise())
  }

  fun protocolUrl(url: String, kind: MapResourceKind): String =
    "$scheme://${encodeResourceUrl(kind.value)}/${encodeResourceUrl(url)}"

  fun parseProtocolUrl(protocolUrl: String): MapResourceRequest {
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
  randomize(bytes)
  val token =
    buildString(32) {
      for (index in 0 until 16) {
        val value = byteAt(bytes, index)
        append("0123456789abcdef"[value ushr 4])
        append("0123456789abcdef"[value and 0x0f])
      }
    }
  return "mlc-res-$token"
}

private val undefined: JsAny? = js("undefined")

/** The kind that MapLibre GL JS names with this resource type, or [MapResourceKind.Unknown]. */
internal fun String?.toResourceKind(): MapResourceKind =
  if (isNullOrEmpty()) MapResourceKind.Unknown else MapResourceKind(this)

/** Parses a kind that [GlJsRequestController.protocolUrl] stored. */
internal fun String.toStoredResourceKind(): MapResourceKind =
  MapResourceKind(decodeResourceUrl(this))

private fun requestParameters(url: String, headers: Map<String, String>): RequestParameters {
  val params = unsafeJso<RequestParameters> { this.url = url }
  if (headers.isNotEmpty()) {
    val values = unsafeJso<JsAny>()
    headers.forEach { (name, value) -> setHeader(values, name, value) }
    params.headers = values
  }
  return params
}

private fun setHeader(headers: JsAny, name: String, value: String): Unit =
  js("{ headers[name] = value }")

private fun randomize(bytes: Uint8Array<ArrayBuffer>): Unit =
  js("{ crypto.getRandomValues(bytes) }")

private fun byteAt(bytes: Uint8Array<ArrayBuffer>, index: Int): Int = js("bytes[index]")

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
 * The rejection of a protocol load, with [status] and [url] set for MapLibre GL JS to read as it
 * reads an HTTP failure. [GlJsRequestController] converts it to a plain JS error at the promise
 * boundary so its message survives MapLibre's worker boundary on both Kotlin/JS and Kotlin/Wasm.
 */
internal fun resourceLoadError(message: String, status: Int?, url: String): Throwable {
  return ResourceLoadFailure(message, status, url)
}

/**
 * Converts a load result to the protocol promise outcome of the corresponding HTTP response.
 *
 * MapLibre GL JS loads a vector tile in a worker, and the copy of an error that reaches the worker
 * has no status. A 404 for such a [workerTile] therefore resolves as an empty tile, which is what
 * MapLibre GL JS makes of a 404 vector tile that it fetches itself.
 */
private fun MapResourceLoad.toProtocolResponse(
  url: String,
  workerTile: Boolean,
): ProtocolResponse {
  val expires = expires?.let { Date(it.toEpochMilliseconds().toDouble()) }
  return when (this) {
    is MapResourceLoad.Bytes -> bytes.toProtocolResponse(expires)
    is MapResourceLoad.NoContent -> ByteArray(0).toProtocolResponse(expires)
    is MapResourceLoad.NotModified ->
      throw resourceLoadError(
        "Resource provider returned NotModified for $url, but the browser sends no validators",
        status = null,
        url,
      )
    is MapResourceLoad.Failed ->
      if (workerTile && reason == MapResourceError.NotFound) {
        ByteArray(0).toProtocolResponse(expires)
      } else {
        throw resourceLoadError(message, reason.httpStatus(), url)
      }
  }
}

private fun ByteArray.toProtocolResponse(expires: Date?): ProtocolResponse {
  val bytes = Uint8Array<ArrayBuffer>(size)
  forEachIndexed { index, byte -> setUint8At(bytes, index, byte.toInt() and 0xFF) }
  return unsafeJso {
    data = bytes.buffer
    if (expires != null) this.expires = expires
  }
}

/** Carries HTTP metadata until the protocol promise constructs a plain JS rejection value. */
private class ResourceLoadFailure(message: String, val status: Int?, val url: String) :
  Exception(message)

private fun protocolError(message: String, status: Int?, url: String): JsAny =
  js("{ const error = new Error(message); error.status = status; error.url = url; return error; }")

private fun unwrapProtocolResult(promise: Promise<JsAny>): Promise<ProtocolResponse> =
  js("promise.then(value => { if (value instanceof Error) throw value; return value; })")
