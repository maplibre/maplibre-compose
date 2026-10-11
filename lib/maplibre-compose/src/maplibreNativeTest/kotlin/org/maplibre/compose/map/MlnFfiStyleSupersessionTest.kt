@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.map

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding

class MlnFfiStyleSupersessionTest {

  @Test
  fun a_stalled_uri_document_is_cancelled_without_completing_it() {
    val first = HeldDocument()
    val config = MapResourceConfig(provider = MapResourceProvider("held") { first.load() })
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.pumpUntil("the first document request", timeout = 5.seconds) {
        first.started.count == 0L
      }

      fixture.loadStyle(style("replacement"), timeout = 5.seconds)

      assertTrue(first.cancelled.await(TimeoutMillis), "the old provider was not cancelled")
      assertTrue("replacement" in fixture.session.currentStyleLayerIds())
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }

  @Test
  fun rapid_uri_a_b_c_replacement_cancels_both_obsolete_documents() {
    val first = HeldDocument()
    val second = HeldDocument()
    val config =
      MapResourceConfig(
        provider =
          MapResourceProvider("held") {
            if (it.url == "held://first") first.load() else second.load()
          }
      )
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.pumpUntil("the first document request", timeout = 5.seconds) {
        first.started.count == 0L
      }
      fixture.session.setBaseStyle(BaseStyle.Uri("held://second"))
      fixture.pumpUntil("the second document request", timeout = 5.seconds) {
        second.started.count == 0L
      }
      assertTrue(first.cancelled.await(TimeoutMillis))
      fixture.loadStyle(style("third"), timeout = 5.seconds)
      assertTrue(second.cancelled.await(TimeoutMillis))

      fixture.pump()
      assertEquals(1, fixture.engineEvents.count { it == MapEvent.StyleLoaded })
      assertTrue("third" in fixture.session.currentStyleLayerIds())
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }

  @Test
  fun a_failed_replacement_reports_once_and_a_later_style_recovers() {
    val first = HeldDocument()
    val config = MapResourceConfig(provider = MapResourceProvider("held") { first.load() })
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.pumpUntil("the first document request", timeout = 5.seconds) {
        first.started.count == 0L
      }
      fixture.session.setBaseStyle(BaseStyle.Json("{invalid"))
      fixture.pumpUntil("the replacement failure", timeout = 5.seconds) {
        fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
      }
      assertTrue(first.cancelled.await(TimeoutMillis))
      fixture.loadStyle(style("recovered"), timeout = 5.seconds)
      fixture.pump()
      assertEquals(1, fixture.errors.size)
      assertEquals(1, fixture.engineEvents.count { it is MapEvent.StyleLoadFailed })
      assertTrue("recovered" in fixture.session.currentStyleLayerIds())
    }
  }

  @Test
  fun a_setter_rejected_before_native_cannot_adopt_the_old_documents_late_success() {
    val first = HeldDocument()
    val config = MapResourceConfig(provider = MapResourceProvider("held") { first.load() })
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.pumpUntil("the first document request", timeout = 5.seconds) {
        first.started.count == 0L
      }
      fixture.session.setBaseStyle(BaseStyle.Uri("held://invalid\u0000url"))
      fixture.pumpUntil("the setter rejection", timeout = 5.seconds) {
        fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
      }
      first.complete(style("obsolete"))
      fixture.pump()
      assertEquals(0, fixture.engineEvents.count { it == MapEvent.StyleLoaded })
      assertEquals(1, fixture.errors.size)
      fixture.loadStyle(style("recovered"), timeout = 5.seconds)
      assertTrue("recovered" in fixture.session.currentStyleLayerIds())
    }
  }

  @Test
  fun a_provider_failure_after_supersession_reports_the_current_request_and_recovers() {
    val first = HeldDocument()
    val config =
      MapResourceConfig(
        provider =
          MapResourceProvider("held") {
            if (it.url == "held://first") first.load() else error("replacement document failed")
          }
      )
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.pumpUntil("the first document request", timeout = 5.seconds) {
        first.started.count == 0L
      }
      fixture.session.setBaseStyle(BaseStyle.Uri("held://failure"))
      fixture.pumpUntil("the provider failure", timeout = 5.seconds) {
        fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
      }
      assertTrue(first.cancelled.await(TimeoutMillis))
      assertTrue(fixture.errors.single().contains("replacement document failed"))
      fixture.loadStyle(style("recovered"), timeout = 5.seconds)
      assertEquals(1, fixture.engineEvents.count { it is MapEvent.StyleLoadFailed })
    }
  }

  @Test
  fun a_loaded_callback_can_assign_the_next_style() {
    BridgeMapFixture.create().use { fixture ->
      val callbacks = fixture.session.callbacks
      var replaced = false
      fixture.session.callbacks =
        object : MapAdapter.Callbacks by callbacks {
          override fun onStyleChanged(map: MapAdapter, style: StyleBinding?) {
            callbacks.onStyleChanged(map, style)
            if (style != null && !replaced) {
              replaced = true
              map.setBaseStyle(style("nested"))
            }
          }
        }
      fixture.session.setBaseStyle(style("outer"))
      fixture.pumpUntil("the callback replacement", timeout = 5.seconds) {
        "nested" in fixture.session.currentStyleLayerIds() && fixture.style?.isLoaded == true
      }
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }

  private class HeldDocument {
    val started = TestLatch(1)
    val cancelled = TestLatch(1)
    val continuation = AtomicReference<CancellableContinuation<ByteArray>?>(null)

    fun complete(style: BaseStyle.Json) {
      checkNotNull(continuation.load()).resume(style.json.encodeToByteArray())
    }

    suspend fun load(): ByteArray = suspendCancellableCoroutine { pending ->
      continuation.store(pending)
      pending.invokeOnCancellation { cancelled.countDown() }
      started.countDown()
    }
  }

  private companion object {
    const val TimeoutMillis = 5_000L

    fun style(id: String) =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"$id","type":"background"}]}""")
  }
}
