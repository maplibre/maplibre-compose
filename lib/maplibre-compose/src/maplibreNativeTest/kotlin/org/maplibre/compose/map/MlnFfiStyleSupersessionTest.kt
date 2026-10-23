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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.resource.MapResourceConfig
import org.maplibre.compose.resource.MapResourceProvider
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.style.StyleBinding
import org.maplibre.compose.testing.runMapTest

class MlnFfiStyleSupersessionTest {

  @Test
  fun a_stalled_uri_document_is_cancelled_without_completing_it() = runMapTest {
    val first = HeldDocument()
    val config = MapResourceConfig(provider = MapResourceProvider("held") { first.load() })
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.awaitUntil("the first document request", Timeout) { first.started.isCompleted }

      fixture.loadStyle(style("replacement"), Timeout)

      fixture.awaitUntil("the old provider to be cancelled", Timeout) {
        first.cancelled.isCompleted
      }
      assertTrue("replacement" in fixture.session.currentStyleLayerIds())
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }

  @Test
  fun rapid_uri_a_b_c_replacement_cancels_both_obsolete_documents() = runMapTest {
    val first = HeldDocument()
    val second = HeldDocument()
    val config =
      MapResourceConfig(
        provider =
          MapResourceProvider("held") {
            if (it.url == "held://first") first.load() else second.load()
          },
      )
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.awaitUntil("the first document request", Timeout) { first.started.isCompleted }
      fixture.session.setBaseStyle(BaseStyle.Uri("held://second"))
      fixture.awaitUntil("the second document request", Timeout) { second.started.isCompleted }
      fixture.awaitUntil("the first provider to be cancelled", Timeout) {
        first.cancelled.isCompleted
      }
      fixture.loadStyle(style("third"), Timeout)
      fixture.awaitUntil("the second provider to be cancelled", Timeout) {
        second.cancelled.isCompleted
      }

      fixture.awaitFrames()
      assertEquals(1, fixture.engineEvents.count { it == MapEvent.StyleLoaded })
      assertTrue("third" in fixture.session.currentStyleLayerIds())
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }

  @Test
  fun a_failed_replacement_reports_once_and_a_later_style_recovers() = runMapTest {
    val first = HeldDocument()
    val config = MapResourceConfig(provider = MapResourceProvider("held") { first.load() })
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.awaitUntil("the first document request", Timeout) { first.started.isCompleted }
      fixture.session.setBaseStyle(BaseStyle.Json("{invalid"))
      fixture.awaitUntil("the replacement failure", Timeout) {
        fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
      }
      fixture.awaitUntil("the old provider to be cancelled", Timeout) {
        first.cancelled.isCompleted
      }
      fixture.loadStyle(style("recovered"), Timeout)
      fixture.awaitFrames()
      assertEquals(1, fixture.errors.size)
      assertEquals(1, fixture.engineEvents.count { it is MapEvent.StyleLoadFailed })
      assertTrue("recovered" in fixture.session.currentStyleLayerIds())
    }
  }

  @Test
  fun a_new_presentation_keeps_a_failed_replacement_failed() = runMapTest {
    BridgeMapFixture.create().use { fixture ->
      fixture.loadStyle(style("loaded"), Timeout)
      fixture.session.setBaseStyle(BaseStyle.Json("{invalid"))
      fixture.awaitUntil("the replacement failure", Timeout) {
        fixture.state.style.loadState is StyleLoadState.Failed
      }
      // A new presentation republishes the retained style. The replaced one is no longer it.
      fixture.session.publishRetainedStyle()
      assertTrue(fixture.state.style.loadState is StyleLoadState.Failed)
    }
  }

  @Test
  fun a_setter_rejected_before_native_cannot_adopt_the_old_documents_late_success() = runMapTest {
    val first = HeldDocument()
    val config = MapResourceConfig(provider = MapResourceProvider("held") { first.load() })
    BridgeMapFixture.create(resourceConfig = config).use { fixture ->
      fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
      fixture.awaitUntil("the first document request", Timeout) { first.started.isCompleted }
      fixture.session.setBaseStyle(BaseStyle.Uri("held://invalid\u0000url"))
      fixture.awaitUntil("the setter rejection", Timeout) {
        fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
      }
      first.complete(style("obsolete"))
      fixture.awaitFrames()
      assertEquals(0, fixture.engineEvents.count { it == MapEvent.StyleLoaded })
      assertEquals(1, fixture.errors.size)
      fixture.loadStyle(style("recovered"), Timeout)
      assertTrue("recovered" in fixture.session.currentStyleLayerIds())
    }
  }

  @Test
  fun a_provider_failure_after_supersession_reports_the_current_request_and_recovers() =
    runMapTest {
      val first = HeldDocument()
      val config =
        MapResourceConfig(
          provider =
            MapResourceProvider("held") {
              if (it.url == "held://first") first.load() else error("replacement document failed")
            },
        )
      BridgeMapFixture.create(resourceConfig = config).use { fixture ->
        fixture.session.setBaseStyle(BaseStyle.Uri("held://first"))
        fixture.awaitUntil("the first document request", Timeout) { first.started.isCompleted }
        fixture.session.setBaseStyle(BaseStyle.Uri("held://failure"))
        fixture.awaitUntil("the provider failure", Timeout) {
          fixture.engineEvents.any { it is MapEvent.StyleLoadFailed }
        }
        fixture.awaitUntil("the old provider to be cancelled", Timeout) {
          first.cancelled.isCompleted
        }
        assertTrue(fixture.errors.single().contains("replacement document failed"))
        fixture.loadStyle(style("recovered"), Timeout)
        assertEquals(1, fixture.engineEvents.count { it is MapEvent.StyleLoadFailed })
      }
    }

  @Test
  fun a_loaded_callback_can_assign_the_next_style() = runMapTest {
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
      fixture.awaitUntil("the callback replacement", Timeout) {
        "nested" in fixture.session.currentStyleLayerIds() && fixture.style?.isLoaded == true
      }
      assertTrue(fixture.errors.isEmpty(), fixture.errors.toString())
    }
  }

  private class HeldDocument {
    val started = CompletableDeferred<Unit>()
    val cancelled = CompletableDeferred<Unit>()
    val continuation = AtomicReference<CancellableContinuation<ByteArray>?>(null)

    fun complete(style: BaseStyle.Json) {
      checkNotNull(continuation.load()).resume(style.json.encodeToByteArray())
    }

    suspend fun load(): ByteArray = suspendCancellableCoroutine { pending ->
      continuation.store(pending)
      pending.invokeOnCancellation { cancelled.complete(Unit) }
      started.complete(Unit)
    }
  }

  private companion object {
    val Timeout = 5.seconds

    fun style(id: String) =
      BaseStyle.Json("""{"version":8,"sources":{},"layers":[{"id":"$id","type":"background"}]}""")
  }
}
