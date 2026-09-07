package org.maplibre.compose.style

import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import js.typedarrays.Uint8Array
import kotlin.js.Promise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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
import org.maplibre.compose.sources.TileCoordinate
import org.maplibre.compose.sources.VectorTileProvider

/**
 * One custom vector source's GL JS protocol registration, serving the provider's tiles under a
 * unique protocol until [close].
 */
internal class GlJsCustomVectorAttachment(
  private val sourceId: String,
  private val provider: VectorTileProvider,
) {
  private class SharedRequest(val work: Deferred<ProtocolResponse>) {
    var clients = 0
  }

  private val protocol = "maplibre-compose-custom-vector-${nextProtocolId++}"

  val tileUrlTemplate: String = "$protocol://tiles/{z}/{x}/{y}"

  private val scope =
    CoroutineScope(
      SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-custom-vector-$sourceId")
    )
  private val requests = mutableMapOf<TileCoordinate, SharedRequest>()
  private var open = true

  init {
    addProtocol(protocol) { request, abortController ->
      loadProtocolTile(request, abortController)
    }
  }

  private fun loadProtocolTile(
    request: RequestParameters,
    abortController: ProtocolAbortController,
  ): Promise<ProtocolResponse> {
    check(open) { "Custom vector source '$sourceId' is detached" }
    val tile = parseTileCoordinate(request.url)
    val shared =
      requests[tile]?.takeUnless { it.work.isCancelled }
        ?: SharedRequest(
            scope.async(start = CoroutineStart.LAZY) {
              val data = provider.loadTile(tile)
              currentCoroutineContext().ensureActive()
              if (!open) throw CancellationException("Custom vector source was detached")
              data.toProtocolResponse()
            }
          )
          .also { requests[tile] = it }
    val work =
      scope.async(start = CoroutineStart.LAZY) {
        shared.work.await()
      }
    shared.clients++

    val signal = abortController.signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion {
      shared.clients--
      if (shared.clients == 0 && requests[tile] === shared) {
        requests.remove(tile)
        if (!shared.work.isCompleted) shared.work.cancel()
      }
      signal.removeEventListener("abort", abort)
    }
    if (signal.aborted == true) work.cancel()
    work.start()
    return work.asPromise()
  }

  fun close() {
    if (!open) return
    open = false
    requests.clear()
    scope.cancel()
    removeProtocol(protocol)
  }

  private companion object {
    var nextProtocolId = 1L
  }
}

private fun parseTileCoordinate(url: String): TileCoordinate {
  val components = url.substringBefore('?').trimEnd('/').split('/')
  require(components.size >= 3) { "Invalid custom vector tile URL: $url" }
  val coordinate = components.takeLast(3)
  return TileCoordinate(
    zoomLevel = coordinate[0].toInt(),
    x = coordinate[1].toLong(),
    y = coordinate[2].toLong(),
  )
}

private fun ByteArray.toProtocolResponse(): ProtocolResponse {
  val bytes = Uint8Array<ArrayBuffer>(size)
  forEachIndexed { index, byte -> setUint8At(bytes, index, byte.toInt() and 0xFF) }
  return unsafeJso { data = bytes.buffer }
}
