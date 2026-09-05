package org.maplibre.compose.style

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.sources.GeoJsonData

@OptIn(ExperimentalCoroutinesApi::class)
class MlnFfiGeoJsonCoordinatorTest {
  @Test
  fun submission_returns_before_preparation_and_only_prepares_the_latest_pending_value() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("first")
      fixture.submit("second")
      fixture.submit("latest")

      assertEquals(emptyList(), fixture.events)
      runCurrent()

      assertEquals(listOf("prepare latest", "install latest", "close latest"), fixture.events)
      fixture.coordinator.awaitLatest()
    }
  }

  @Test
  fun updates_during_preparation_discard_its_result_and_conflate_the_pending_work() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onPrepare = { value ->
        if (value == "first") {
          fixture.submit("second")
          fixture.submit("latest")
        }
      }
      fixture.submit("first")
      runCurrent()

      assertEquals(
        listOf("prepare first", "close first", "prepare latest", "install latest", "close latest"),
        fixture.events,
      )
      fixture.coordinator.awaitLatest()
    }
  }

  @Test
  fun a_uri_supersedes_active_and_pending_inline_data_immediately() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onPrepare = {
        fixture.submit("pending")
        fixture.submitUri("https://example.com/latest.geojson")
      }
      fixture.submit("first")
      runCurrent()

      assertEquals(
        listOf("prepare first", "url https://example.com/latest.geojson", "close first"),
        fixture.events,
      )
      fixture.coordinator.awaitLatest()
    }
  }

  @Test
  fun inline_data_submitted_after_a_uri_replaces_it() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("superseded")
      fixture.submitUri("https://example.com/data.geojson")
      fixture.submit("latest")
      runCurrent()

      assertEquals(
        listOf(
          "url https://example.com/data.geojson",
          "prepare latest",
          "install latest",
          "close latest",
        ),
        fixture.events,
      )
    }
  }

  @Test
  fun installation_checks_currentness_after_returning_to_the_owner_thread() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onInstall = { value ->
        if (value == "first") fixture.submitUri("https://example.com/latest.geojson")
      }
      fixture.submit("first")
      runCurrent()

      assertEquals(
        listOf("prepare first", "url https://example.com/latest.geojson", "close first"),
        fixture.events,
      )
    }
  }

  @Test
  fun closing_during_preparation_releases_the_result_without_installing_it() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onPrepare = { fixture.coordinator.close() }
      fixture.submit("first")
      val waiter = async(start = CoroutineStart.UNDISPATCHED) { fixture.coordinator.awaitLatest() }
      runCurrent()

      assertEquals(listOf("prepare first", "close first"), fixture.events)
      assertEquals(emptyList(), fixture.failures)
      waiter.await()
      assertFailsWith<IllegalStateException> { fixture.submit("after removal") }
    }
  }

  @Test
  fun an_error_from_preparation_after_removal_is_not_reported() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onPrepare = {
        fixture.coordinator.close()
        error("source removed during preparation")
      }
      fixture.submit("first")
      runCurrent()

      assertEquals(listOf("prepare first"), fixture.events)
      assertEquals(emptyList(), fixture.failures)
      fixture.coordinator.awaitLatest()
    }
  }

  @Test
  fun a_failed_update_keeps_installed_data_reports_its_error_and_allows_recovery() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("initial")
      runCurrent()
      val failure = IllegalArgumentException("invalid GeoJSON")
      fixture.onPrepare = { if (it == "invalid") throw failure }

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
      assertEquals("close recovered", fixture.events.last())
    }
  }

  @Test
  fun a_superseded_preparation_failure_does_not_report_or_fail_the_latest_update() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.onPrepare = { value ->
        if (value == "first") {
          fixture.submit("latest")
          error("superseded parse failed")
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
  fun installation_failure_closes_prepared_data_and_reaches_the_waiter() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      val failure = IllegalStateException("native installation failed")
      fixture.onInstall = { throw failure }
      fixture.submit("first")
      runCurrent()

      assertEquals(listOf("prepare first", "close first"), fixture.events)
      assertEquals<List<Throwable>>(listOf(failure), fixture.failures)
      assertSame(
        failure,
        assertFailsWith<IllegalStateException> { fixture.coordinator.awaitLatest() },
      )
    }
  }

  @Test
  fun a_waiter_follows_newer_submissions_and_receives_their_failure() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("first")
      val failure = IllegalArgumentException("latest parse failed")
      fixture.onPrepare = { value ->
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
      assertEquals(listOf("prepare first", "close first", "prepare latest"), fixture.events)
    }
  }

  @Test
  fun unexpected_worker_cancellation_fails_pending_work_and_rejects_new_submissions() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      val failure = CancellationException("native preparation cancelled unexpectedly")
      fixture.onPrepare = {
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
      assertEquals(listOf("prepare first"), fixture.events)
      assertEquals(emptyList(), fixture.failures)
    }
  }

  @Test
  fun an_immediately_resumed_waiter_still_waits_for_the_replacement_data_to_install() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("first")
      val waiter =
        async(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.awaitLatest() }
      assertFalse(waiter.isCompleted)

      fixture.submit("latest")

      assertFalse(waiter.isCompleted)
      assertEquals(emptyList(), fixture.events)
      runCurrent()
      waiter.await()
      assertEquals("latest", fixture.installed)
    }
  }

  @Test
  fun an_immediately_resumed_waiter_does_not_finish_before_a_replacement_uri_installs() = runTest {
    Fixture(StandardTestDispatcher(testScheduler)).use { fixture ->
      fixture.submit("first")
      val waiter =
        async(UnconfinedTestDispatcher(testScheduler)) { fixture.coordinator.awaitLatest() }

      fixture.coordinator.submit(GeoJsonData.Uri("https://example.com/latest.geojson")) { uri ->
        assertFalse(waiter.isCompleted)
        fixture.installed = uri
      }

      waiter.await()
      assertEquals("https://example.com/latest.geojson", fixture.installed)
      runCurrent()
      assertEquals(emptyList(), fixture.events)
    }
  }

  @Test
  fun synchronous_submission_prepares_installs_and_closes_before_returning() = runTest {
    Fixture(StandardTestDispatcher(testScheduler), synchronousUpdate = true).use { fixture ->
      fixture.submit("first")

      assertEquals(listOf("prepare first", "install first", "close first"), fixture.events)
      fixture.coordinator.awaitLatest()
      fixture.submit("second")

      assertEquals("second", fixture.installed)
      assertEquals(
        listOf(
          "prepare first",
          "install first",
          "close first",
          "prepare second",
          "install second",
          "close second",
        ),
        fixture.events,
      )
    }
  }

  @Test
  fun synchronous_preparation_failure_throws_keeps_installed_data_and_allows_recovery() = runTest {
    Fixture(StandardTestDispatcher(testScheduler), synchronousUpdate = true).use { fixture ->
      fixture.submit("initial")
      val failure = IllegalArgumentException("invalid GeoJSON")
      fixture.onPrepare = { if (it == "invalid") throw failure }

      assertSame(failure, assertFailsWith<IllegalArgumentException> { fixture.submit("invalid") })
      assertEquals("initial", fixture.installed)
      assertEquals(emptyList(), fixture.failures)
      fixture.coordinator.awaitLatest()

      fixture.submit("recovered")
      fixture.coordinator.awaitLatest()

      assertEquals("recovered", fixture.installed)
      assertEquals("close recovered", fixture.events.last())
    }
  }

  @Test
  fun synchronous_installation_failure_closes_data_throws_and_allows_recovery() = runTest {
    Fixture(StandardTestDispatcher(testScheduler), synchronousUpdate = true).use { fixture ->
      fixture.submit("initial")
      val failure = IllegalStateException("native installation failed")
      fixture.onInstall = { if (it == "invalid") throw failure }

      assertSame(failure, assertFailsWith<IllegalStateException> { fixture.submit("invalid") })
      assertEquals("initial", fixture.installed)
      assertEquals("close invalid", fixture.events.last())
      assertEquals(emptyList(), fixture.failures)
      fixture.coordinator.awaitLatest()

      fixture.submit("recovered")
      fixture.coordinator.awaitLatest()

      assertEquals("recovered", fixture.installed)
      assertEquals("close recovered", fixture.events.last())
    }
  }

  @Test
  fun closing_during_synchronous_preparation_releases_the_result_without_installation() = runTest {
    Fixture(StandardTestDispatcher(testScheduler), synchronousUpdate = true).use { fixture ->
      fixture.onPrepare = { fixture.coordinator.close() }

      fixture.submit("first")

      assertEquals(listOf("prepare first", "close first"), fixture.events)
      assertEquals(emptyList(), fixture.failures)
      fixture.coordinator.awaitLatest()
      assertFailsWith<IllegalStateException> { fixture.submit("after removal") }
    }
  }

  @Test
  fun a_reentrant_synchronous_update_supersedes_the_outer_installation_and_closes_both_results() =
    runTest {
      Fixture(StandardTestDispatcher(testScheduler), synchronousUpdate = true).use { fixture ->
        fixture.onInstall = { if (it == "first") fixture.submit("latest") }

        fixture.submit("first")

        assertEquals("latest", fixture.installed)
        assertEquals(
          listOf(
            "prepare first",
            "prepare latest",
            "install latest",
            "close latest",
            "close first",
          ),
          fixture.events,
        )
        fixture.coordinator.awaitLatest()
      }
    }

  private class Fixture(
    dispatcher: CoroutineDispatcher,
    synchronousUpdate: Boolean = false,
  ) : AutoCloseable {
    val events = mutableListOf<String>()
    val failures = mutableListOf<Throwable>()
    var installed: String? = null
    var onPrepare: (String) -> Unit = {}
    var onInstall: (String) -> Unit = {}
    val coordinator =
      MlnFfiGeoJsonCoordinator(
        prepare = { data ->
          val value = (data as GeoJsonData.JsonString).json
          events += "prepare $value"
          onPrepare(value)
          Prepared(value) { events += "close $value" }
        },
        install = { prepared, isCurrent ->
          onInstall(prepared.value)
          if (isCurrent()) {
            installed = prepared.value
            events += "install ${prepared.value}"
          }
        },
        reportFailure = { error, isCurrent -> if (isCurrent()) failures += error },
        synchronousUpdate = synchronousUpdate,
        dispatcher = dispatcher,
      )

    fun submit(value: String) =
      coordinator.submit(GeoJsonData.JsonString(value)) { error("Not a URI") }

    fun submitUri(uri: String) =
      coordinator.submit(GeoJsonData.Uri(uri)) {
        installed = it
        events += "url $it"
      }

    override fun close() = coordinator.close()
  }

  private class Prepared(val value: String, private val onClose: () -> Unit) : AutoCloseable {
    override fun close() = onClose()
  }
}
