package org.maplibre.compose.resource

import js.buffer.ArrayBuffer
import js.objects.unsafeJso
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.gljs.ProtocolAbortController
import org.maplibre.compose.gljs.ProtocolResponse
import org.maplibre.compose.gljs.RequestParameters

class GlJsProtocolInteropTest {
  @Test
  fun protocol_payload_is_a_javascript_array_buffer() = runTest {
    withController(MapResourceLoad.Bytes(byteArrayOf(0, 127, -1))) { controller, request ->
      val response = controller.loadProtocol(request, abortController()).await()
      assertEquals("0,127,255", byteValues(response.data))
    }
  }

  @Test
  fun missing_tiles_reject_with_the_javascript_http_status() = runTest {
    withController(MapResourceLoad.Failed(MapResourceError.NotFound, "missing tile")) {
      controller,
      request ->
      assertEquals(
        404.0,
        org.maplibre.compose.gljs.jsNumberToDouble(
          rejectionStatus(controller.loadProtocol(request, abortController())).await()
        ),
      )
    }
  }

  private suspend fun withController(
    result: MapResourceLoad,
    action: suspend (GlJsRequestController, RequestParameters) -> Unit,
  ) {
    val controller =
      GlJsRequestController(
        MapResourceConfig(provider = MapResourceProvider(accepts = { true }, load = { result }))
      )
    try {
      action(
        controller,
        unsafeJso {
          url = controller.protocolUrl("app://tile", MapResourceKind.Tile)
        },
      )
    } finally {
      controller.close()
    }
  }
}

private fun abortController(): ProtocolAbortController = js("new AbortController()")

private fun byteValues(buffer: ArrayBuffer): String =
  js("Array.from(new Uint8Array(buffer)).join(',')")

private fun rejectionStatus(promise: Promise<ProtocolResponse>): Promise<kotlin.js.JsNumber> =
  js(
    "promise.then(() => { throw new Error('Expected rejection'); }, error => { if (typeof error.status !== 'number') throw new Error('Missing JavaScript status'); return error.status; })"
  )
