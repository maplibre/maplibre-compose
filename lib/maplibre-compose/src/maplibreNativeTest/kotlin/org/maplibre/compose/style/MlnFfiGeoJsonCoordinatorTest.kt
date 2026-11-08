package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Position

@OptIn(ExperimentalCoroutinesApi::class)
class MlnFfiGeoJsonCoordinatorTest {
  @Test
  fun submission_returns_before_serialization_and_only_serializes_the_latest_pending_value() =
    runTest {
      Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
        fixture.submit("first")
        fixture.submit("second")
        fixture.submit("latest")

        assertEquals(emptyList(), fixture.events)
        runCurrent()

        assertEquals(listOf("serialize latest", "install latest"), fixture.events)
        fixture.coordinator.awaitLatest()
      }
    }

  @Test
  fun updates_during_serialization_discard_its_result_and_conflate_the_pending_work() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onSerialize = { value ->
        if (value == "first") {
          fixture.submit("second")
          fixture.submit("latest")
        }
      }
      fixture.submit("first")
      runCurrent()

      assertEquals(
        listOf("serialize first", "serialize latest", "install latest"),
        fixture.events,
      )
      fixture.coordinator.awaitLatest()
    }
  }

  @Test
  fun json_and_urls_install_at_once_and_supersede_active_and_pending_features() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onSerialize = {
        fixture.submit("pending")
        fixture.submitJson("inline")
        fixture.submitUri("https://example.com/latest.geojson")
      }
      fixture.submit("first")
      runCurrent()

      assertEquals(
        listOf(
          "serialize first",
          "install inline",
          "url https://example.com/latest.geojson",
        ),
        fixture.events,
      )
      fixture.coordinator.awaitLatest()
    }
  }

  @Test
  fun features_submitted_after_a_url_install_after_it() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("superseded")
      fixture.submitUri("https://example.com/data.geojson")
      fixture.submit("latest")
      runCurrent()

      assertEquals(
        listOf("url https://example.com/data.geojson", "serialize latest", "install latest"),
        fixture.events,
      )
    }
  }

  @Test
  fun closing_during_serialization_drops_the_result() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onSerialize = { fixture.coordinator.close() }
      fixture.submit("first")
      val waiter = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.awaitLatest() }
      runCurrent()

      assertEquals(listOf("serialize first"), fixture.events)
      assertEquals(emptyList(), fixture.failures)
      waiter.await()
      assertFailsWith<IllegalStateException> { fixture.submit("after removal") }
    }
  }

  @Test
  fun a_failed_serialization_keeps_installed_data_reports_its_error_and_allows_recovery() =
    runTest {
      Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
        fixture.submit("initial")
        runCurrent()
        val failure = IllegalArgumentException("unserializable")
        fixture.onSerialize = { if (it == "invalid") throw failure }

        fixture.submit("invalid")
        runCurrent()

        assertEquals("initial", fixture.installed)
        assertEquals<List<Throwable>>(listOf(failure), fixture.failures)
        assertSame(
          failure,
          assertFailsWith<IllegalArgumentException> { fixture.coordinator.awaitLatest() },
        )

        fixture.submit("recovered")
        runCurrent()
        fixture.coordinator.awaitLatest()

        assertEquals("recovered", fixture.installed)
      }
    }

  @Test
  fun a_superseded_serialization_failure_does_not_report_or_fail_the_latest_update() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onSerialize = { value ->
        if (value == "first") {
          fixture.submit("latest")
          error("superseded serialization failed")
        }
      }
      fixture.submit("first")
      runCurrent()
      fixture.coordinator.awaitLatest()

      assertEquals("latest", fixture.installed)
      assertEquals(emptyList(), fixture.failures)
    }
  }

  @Test
  fun a_waiter_waits_for_the_native_outcome_and_receives_its_failure() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      val outcome = CompletableDeferred<Unit>()
      fixture.outcome = { outcome }
      fixture.submitJson("rejected")
      val waiter =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { fixture.coordinator.awaitLatest() }
        }
      runCurrent()
      assertFalse(waiter.isCompleted)

      val failure = IllegalStateException("native rejected the data")
      outcome.completeExceptionally(failure)
      runCurrent()

      // Coroutines may copy an exception that crosses a resumption, so compare what it says.
      assertEquals(failure.message, waiter.await().exceptionOrNull()?.message)
      assertEquals(listOf(failure.message), fixture.failures.map { it.message })
    }
  }

  @Test
  fun a_failure_after_the_source_is_removed_is_reported_as_stale() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      val outcome = CompletableDeferred<Unit>()
      fixture.outcome = { outcome }
      fixture.submitUri("https://example.com/data.geojson")
      fixture.coordinator.close()
      outcome.completeExceptionally(IllegalStateException("the source is gone"))
      runCurrent()

      assertEquals(emptyList(), fixture.failures)
      assertEquals(listOf("the source is gone"), fixture.stale.map { it.message })
    }
  }

  @Test
  fun a_superseded_native_failure_is_not_reported() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      val first = CompletableDeferred<Unit>()
      fixture.outcome = { if (it == "first") first else CompletableDeferred(Unit) }
      fixture.submitJson("first")
      fixture.submitJson("latest")
      first.completeExceptionally(IllegalStateException("superseded data failed"))
      runCurrent()

      fixture.coordinator.awaitLatest()
      assertEquals(emptyList(), fixture.failures)
      assertEquals(emptyList(), fixture.stale)
      assertEquals("latest", fixture.installed)
    }
  }

  @Test
  fun a_waiter_follows_newer_submissions_and_receives_their_failure() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("first")
      val failure = IllegalArgumentException("latest serialization failed")
      fixture.onSerialize = { value ->
        if (value == "first") fixture.submit("latest") else throw failure
      }
      val waiter =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { fixture.coordinator.awaitLatest() }
        }
      assertFalse(waiter.isCompleted)
      runCurrent()

      assertSame(failure, waiter.await().exceptionOrNull())
      assertEquals<List<Throwable>>(listOf(failure), fixture.failures)
      assertEquals(listOf("serialize first", "serialize latest"), fixture.events)
    }
  }

  @Test
  fun unexpected_worker_cancellation_fails_pending_work_and_rejects_new_submissions() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      val failure = CancellationException("serialization cancelled unexpectedly")
      fixture.onSerialize = {
        fixture.submit("pending")
        throw failure
      }
      fixture.submit("first")
      val waiter =
        async(start = CoroutineStart.UNDISPATCHED) {
          runCatching { fixture.coordinator.awaitLatest() }
        }
      runCurrent()

      assertSame(failure, waiter.await().exceptionOrNull())
      assertSame(
        failure,
        assertFailsWith<CancellationException> { fixture.coordinator.awaitLatest() },
      )
      assertFailsWith<IllegalStateException> { fixture.submit("after worker failure") }
      assertEquals(listOf("serialize first"), fixture.events)
      assertEquals(emptyList(), fixture.failures)
    }
  }

  private class Fixture(dispatcher: CoroutineDispatcher) : AutoCloseable {
    val events = mutableListOf<String>()
    val failures = mutableListOf<Throwable>()
    val stale = mutableListOf<Throwable>()
    var installed: String? = null
    var onSerialize: (String) -> Unit = {}
    var outcome: (String) -> Deferred<Unit> = { CompletableDeferred(Unit) }
    val coordinator =
      MlnFfiGeoJsonCoordinator(
        install = { data ->
          val value =
            when (data) {
              is GeoJsonData.JsonString -> data.json.also { events += "install $it" }
              is GeoJsonData.Uri -> data.uri.also { events += "url $it" }
              is GeoJsonData.Features -> error("Features must serialize first")
            }
          installed = value
          outcome(value)
        },
        reportFailure = { error, isCurrent ->
          if (isCurrent) failures += error else stale += error
        },
        mainDispatcher = dispatcher,
        workerDispatcher = dispatcher,
        serialize = { data ->
          val properties = (data.geoJson as Feature<*, *>).properties as JsonObject
          val value = (properties.getValue("label") as JsonPrimitive).content
          events += "serialize $value"
          onSerialize(value)
          value
        },
      )

    fun submit(label: String) =
      coordinator.submit(
        GeoJsonData.Features(
          Feature(Point(Position(0.0, 0.0)), buildJsonObject { put("label", label) }),
        ),
      )

    fun submitJson(json: String) = coordinator.submit(GeoJsonData.JsonString(json))

    fun submitUri(uri: String) = coordinator.submit(GeoJsonData.Uri(uri))

    override fun close() = coordinator.close()
  }
}
