package org.maplibre.compose.resource

import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
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
  val scheme = "mlc-res-${nextId++}"
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
    val incoming = MapResourceRequest(url, kind)
    val transform = config.interceptor().transform(incoming)
    val nextUrl = transform.url ?: url
    val accepted = config.provider?.accepts(MapResourceRequest(nextUrl, kind)) == true
    if (!accepted && transform.url == null && transform.headers.isEmpty()) return undefined
    return requestParameters(
      url = if (accepted) protocolUrl(nextUrl, kind) else nextUrl,
      headers = transform.headers,
    )
  }

  private fun loadProtocol(
    request: RequestParameters,
    abortController: Any,
  ): Promise<ProtocolResponse> {
    val parsed = parseProtocolUrl(request.url)
    val work = scope.async {
      val provider =
        config.provider ?: throw IllegalStateException("No resource provider is installed")
      when (val result = provider.load(MapResourceRequest(parsed.url, parsed.kind))) {
        is MapResourceLoad.Bytes -> result.bytes.toProtocolResponse()
        is MapResourceLoad.Failed -> throw IllegalStateException(result.message)
      }
    }
    val signal = abortController.asDynamic().signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion { signal.removeEventListener("abort", abort) }
    if (signal.aborted == true) work.cancel()
    return work.asPromise()
  }

  fun protocolUrl(url: String, kind: MapResourceKind): String =
    "$scheme://${kind.name}/${encodeResourceUrl(url)}"

  fun parseProtocolUrl(protocolUrl: String): MapResourceRequest {
    val remainder = protocolUrl.substringAfter("://")
    val separator = remainder.indexOf('/')
    require(separator > 0) { "Invalid resource protocol URL: $protocolUrl" }
    val kind =
      remainder.substring(0, separator).toResourceKind().takeUnless {
        it == MapResourceKind.Unknown
      } ?: MapResourceKind.Unknown
    return MapResourceRequest(decodeResourceUrl(remainder.substring(separator + 1)), kind)
  }

  override fun close() {
    if (!open) return
    open = false
    scope.cancel()
    if (protocolInstalled) removeProtocol(scheme)
  }

  private companion object {
    var nextId = 1L
  }
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
    else -> MapResourceKind.Unknown
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

private fun ByteArray.toProtocolResponse(): ProtocolResponse {
  val bytes = Uint8Array<ArrayBuffer>(size)
  forEachIndexed { index, byte -> bytes.asDynamic()[index] = byte.toInt() and 0xFF }
  return unsafeJso { data = bytes.buffer }
}
