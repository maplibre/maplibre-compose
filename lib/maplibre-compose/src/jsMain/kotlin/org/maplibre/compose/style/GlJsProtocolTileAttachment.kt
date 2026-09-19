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
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.RequestParameters
import org.maplibre.compose.gljs.addProtocol
import org.maplibre.compose.gljs.removeProtocol
import org.maplibre.compose.sources.TileCoordinate

/**
 * One source's GL JS protocol registration, serving the tiles [loadTile] produces under a unique
 * protocol until [close].
 */
internal class GlJsProtocolTileAttachment(
  private val name: String,
  private val loadTile: suspend (TileCoordinate) -> ByteArray,
) {
  private class SharedRequest(val work: Deferred<ByteArray>) {
    var clients = 0
  }

  private val protocol = "maplibre-compose-tile-${nextProtocolId++}"

  private var generation = 0L

  /**
   * The URL template GL JS fetches tiles with. [invalidate] changes [generation], which changes the
   * template, so a reloaded source can never read another generation's cached tile.
   */
  val tileUrlTemplate: String
    get() = "$protocol://tiles/{z}/{x}/{y}?v=$generation"

  private val scope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("maplibre-$name"))
  private val requests = mutableMapOf<TileCoordinate, SharedRequest>()
  private var open = true

  init {
    addProtocol(protocol) { request, abortController ->
      loadProtocolTile(request, abortController)
    }
  }

  private fun loadProtocolTile(
    request: RequestParameters,
    abortController: Any,
  ): Promise<ProtocolResponse> {
    check(open) { "Protocol tile attachment '$name' is detached" }
    val tile = parseTileCoordinate(request.url)
    val shared =
      requests[tile]?.takeUnless { it.work.isCancelled }
        ?: SharedRequest(
            scope.async(start = CoroutineStart.LAZY) {
              val data =
                try {
                  loadTile(tile)
                } catch (cancellation: CancellationException) {
                  throw cancellation
                } catch (error: Throwable) {
                  throw protocolFailure(error)
                }
              currentCoroutineContext().ensureActive()
              if (!open)
                throw CancellationException("Protocol tile attachment '$name' was detached")
              data
            }
          )
          .also { requests[tile] = it }
    val work =
      scope.async(start = CoroutineStart.LAZY) {
        shared.work.await().toProtocolResponse()
      }
    shared.clients++

    val signal = abortController.asDynamic().signal
    val abort: () -> Unit = { work.cancel() }
    signal.addEventListener("abort", abort)
    work.invokeOnCompletion {
      shared.clients--
      if (shared.clients == 0) {
        if (requests[tile] === shared) requests.remove(tile)
        if (!shared.work.isCompleted) shared.work.cancel()
      }
      signal.removeEventListener("abort", abort)
    }
    if (signal.aborted == true) work.cancel()
    work.start()
    return work.asPromise()
  }

  /** Changes the URL so GL JS cannot reuse a cached response after invalidation. */
  fun invalidate(): String {
    if (open) {
      generation++
      requests.clear()
    }
    return tileUrlTemplate
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
  require(components.size >= 3) { "Invalid protocol tile URL: $url" }
  val coordinate = components.takeLast(3)
  return TileCoordinate(
    zoomLevel = coordinate[0].toInt(),
    x = coordinate[1].toLong(),
    y = coordinate[2].toLong(),
  )
}

/**
 * A protocol failure as a plain JS error. MapLibre copies a tile error through its worker boundary,
 * and a Kotlin exception's non-enumerable `message` does not survive that copy, while a JS error's
 * does.
 */
private fun protocolFailure(error: Throwable): Throwable {
  val jsError = js("new Error()")
  jsError.message = error.message ?: error.toString()
  return jsError.unsafeCast<Throwable>()
}

private fun ByteArray.toProtocolResponse(): ProtocolResponse {
  val bytes = Uint8Array<ArrayBuffer>(size)
  forEachIndexed { index, byte -> bytes.asDynamic()[index] = byte.toInt() and 0xFF }
  return unsafeJso { data = bytes.buffer }
}
