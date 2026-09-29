package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.style.BaseStyle

@OptIn(ExperimentalCoroutinesApi::class)
class RetainedEngineLifecycleTest {

  private fun TestScope.lifecycleOf(steps: RecordingEngineSteps): RetainedEngineLifecycle =
    mapRuntimeForTest(physicalScope = backgroundScope)
      .createMapState(BaseStyle.Demo)
      .lifecycle
      .createRetainedEngineLifecycle(steps, PresentationTestAdapter())

  @Test
  fun a_map_attaches_with_an_engine_identity_and_render_lease() = runTest {
    val steps = RecordingEngineSteps()
    val lifecycle = lifecycleOf(steps)

    assertNull(lifecycle.engine)
    lifecycle.attach()

    val engine = lifecycle.engine
    val lease = assertNotNull(lifecycle.lease)
    assertEquals(listOf("create $engine", "attach $engine $lease"), steps.commands)
  }

  @Test
  fun detaching_ends_the_lease_before_retained_engine_cleanup_finishes() = runTest {
    val steps = RecordingEngineSteps()
    val lifecycle = lifecycleOf(steps)
    lifecycle.attach()
    val engine = lifecycle.engine
    val departed = lifecycle.lease
    steps.allowDetach = CompletableDeferred()

    val detach = async { lifecycle.detach() }
    steps.detachStarted.await()
    assertNull(lifecycle.lease)
    val reattach = async { lifecycle.attach() }
    runCurrent()
    assertEquals(1, steps.commands.count { it.startsWith("attach ") })

    steps.allowDetach.complete(Unit)
    assertTrue(detach.await())
    reattach.await()
    assertEquals(
      listOf(
        "create $engine",
        "attach $engine $departed",
        "detach $engine $departed",
        "attach $engine ${lifecycle.lease}",
      ),
      steps.commands,
    )
  }

  @Test
  fun attach_failure_cleans_partial_resources_and_returns_to_open_detached() = runTest {
    val steps = RecordingEngineSteps().apply { attachFailure = TestFailure("attach") }
    val lifecycle = lifecycleOf(steps)

    assertFailsWith<TestFailure> { lifecycle.attach() }

    val engine = checkNotNull(lifecycle.engine)
    assertNull(lifecycle.lease)
    assertEquals(
      listOf(
        "create $engine",
        "attach $engine ${steps.lastLease}",
        "detach $engine ${steps.lastLease}",
      ),
      steps.commands,
    )
  }

  @Test
  fun detach_during_attach_invalidates_the_lease_and_cleans_the_partial_attachment() = runTest {
    val steps = RecordingEngineSteps().apply { allowAttach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    val attaching = async { runCatching { lifecycle.attach() } }
    steps.attachStarted.await()

    val detaching = async { lifecycle.detach() }
    runCurrent()

    steps.allowAttach.complete(Unit)
    assertTrue(detaching.await())
    assertTrue(attaching.await().exceptionOrNull() is MapLeaseInvalidatedException)
    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
  }

  @Test
  fun an_attachment_queued_behind_a_failing_detachment_fails_with_it() = runTest {
    val steps =
      RecordingEngineSteps().apply {
        allowDetach = CompletableDeferred()
        detachFailure = TestFailure("detach")
      }
    val lifecycle = lifecycleOf(steps)
    lifecycle.attach()
    val detaching = async { runCatching { lifecycle.detach() } }
    steps.detachStarted.await()
    val reattach = async { runCatching { lifecycle.attach() } }
    runCurrent()

    steps.allowDetach.complete(Unit)

    val failure = assertNotNull(detaching.await().exceptionOrNull())
    assertSame(failure, reattach.await().exceptionOrNull())
    assertNull(lifecycle.lease)
    steps.detachFailure = null
    lifecycle.attach()
    assertEquals(2, steps.commands.count { it.startsWith("attach ") })
  }

  @Test
  fun close_rejects_work_at_once_and_repeated_callers_join_one_cleanup() = runTest {
    val steps = RecordingEngineSteps().apply { allowDetach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    lifecycle.attach()

    lifecycle.close()
    lifecycle.close()

    assertTrue(lifecycle.isClosing)
    assertNull(lifecycle.lease)
    assertFailsWith<MapClosedException> { lifecycle.attach() }
    assertFalse(lifecycle.beginAttach())
    steps.detachStarted.await()
    steps.allowDetach.complete(Unit)
    lifecycle.awaitClosed()

    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
    assertEquals(1, steps.commands.count { it.startsWith("destroy ") })
    assertEquals(1, steps.commands.count { it == "close resources" })
  }

  @Test
  fun cleanup_attempts_every_resource_and_await_closed_reports_every_failure() = runTest {
    val steps =
      RecordingEngineSteps().apply {
        detachFailure = TestFailure("detach")
        destroyFailure = TestFailure("engine")
        resourcesFailure = TestFailure("resources")
      }
    val lifecycle =
      RetainedEngineLifecycle(
        steps,
        scope = backgroundScope,
        mainThread = MainThreadGuard(TestMainDispatcher()),
        onClosing = { throw TestFailure("notification") },
      )
    lifecycle.attach()

    lifecycle.close()
    val failure = assertFailsWith<MapCleanupException> { lifecycle.awaitClosed() }

    assertEquals(
      listOf("notification", "detach", "engine", "resources"),
      failure.failures.map { it.message },
    )
  }

  @Test
  fun cleanup_preserves_a_fatal_error() = runTest {
    val fatal = FatalLifecycleError()
    val lifecycle = lifecycleOf(RecordingEngineSteps().apply { resourcesFailure = fatal })

    lifecycle.close()
    val reported = assertFailsWith<FatalLifecycleError> { lifecycle.awaitClosed() }

    assertSame(fatal, reported)
  }

  @Test
  fun cancelling_an_attach_caller_invalidates_the_lease_but_physical_cleanup_continues() = runTest {
    val steps = RecordingEngineSteps().apply { allowAttach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    val caller = async { lifecycle.attach() }
    steps.attachStarted.await()

    caller.cancelAndJoin()
    assertNull(lifecycle.lease)
    steps.allowAttach.complete(Unit)
    runCurrent()

    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
  }

  @Test
  fun a_detached_retained_engine_keeps_its_identity_without_a_lease() = runTest {
    val steps = RecordingEngineSteps()
    val lifecycle = lifecycleOf(steps)
    lifecycle.attach()
    val engine = checkNotNull(lifecycle.engine)

    assertTrue(lifecycle.detach())

    assertNull(lifecycle.lease)
    assertEquals(engine, lifecycle.engine)
    assertEquals(0, steps.commands.count { it.startsWith("destroy ") })
  }

  @Test
  fun close_joins_a_failing_detach_and_still_attempts_engine_and_resource_cleanup() = runTest {
    val steps =
      RecordingEngineSteps().apply {
        allowDetach = CompletableDeferred()
        detachFailure = TestFailure("detach")
      }
    val lifecycle = lifecycleOf(steps)
    lifecycle.attach()
    val detaching = async { runCatching { lifecycle.detach() } }
    steps.detachStarted.await()

    lifecycle.close()
    steps.allowDetach.complete(Unit)
    val failure = assertFailsWith<MapCleanupException> { lifecycle.awaitClosed() }

    assertEquals(listOf("detach"), failure.failures.map { it.message })
    assertTrue(detaching.await().isFailure)
    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
    assertEquals(1, steps.commands.count { it.startsWith("destroy ") })
    assertTrue("close resources" in steps.commands)
  }

  @Test
  fun a_cancelled_attach_caller_cannot_end_a_later_presentation() = runTest {
    val steps = RecordingEngineSteps().apply { allowAttach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    val departed = async { lifecycle.attach() }
    steps.attachStarted.await()
    val detaching = async { lifecycle.detach() }
    runCurrent()
    assertTrue(lifecycle.beginAttach())

    departed.cancelAndJoin()
    steps.allowAttach.complete(Unit)
    assertTrue(detaching.await())
    runCurrent()

    val current = assertNotNull(lifecycle.lease)
    assertEquals(current, steps.lastLease)
    assertEquals(2, steps.commands.count { it.startsWith("attach ") })
    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
  }

  @Test
  fun close_during_attach_invalidates_the_lease_and_joins_its_cleanup() = runTest {
    val steps = RecordingEngineSteps().apply { allowAttach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    val attaching = async { runCatching { lifecycle.attach() } }
    steps.attachStarted.await()

    lifecycle.close()
    steps.allowAttach.complete(Unit)
    lifecycle.awaitClosed()

    assertTrue(attaching.await().exceptionOrNull() is MapLeaseInvalidatedException)
    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
    assertEquals(1, steps.commands.count { it.startsWith("destroy ") })
  }

  @Test
  fun closing_a_never_attached_map_waits_for_shared_cleanup() = runTest {
    val steps = RecordingEngineSteps().apply { allowResources = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)

    lifecycle.close()
    val closed = async { lifecycle.awaitClosed() }
    runCurrent()
    assertFalse(closed.isCompleted)

    steps.allowResources.complete(Unit)
    closed.await()
    assertEquals(listOf("close resources"), steps.commands)
  }

  @Test
  fun an_attachment_queued_behind_a_detachment_is_inert_after_closure_begins() = runTest {
    val steps = RecordingEngineSteps().apply { allowDetach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    lifecycle.attach()
    val detaching = async { runCatching { lifecycle.detach() } }
    steps.detachStarted.await()
    assertTrue(lifecycle.beginAttach())

    lifecycle.close()
    assertFalse(lifecycle.beginAttach())
    steps.allowDetach.complete(Unit)
    lifecycle.awaitClosed()

    assertTrue(detaching.await().isSuccess)
    assertEquals(1, steps.commands.count { it.startsWith("attach ") })
    assertEquals(1, steps.commands.count { it.startsWith("detach ") })
  }

  @Test
  fun a_late_attach_start_is_inert_after_closure_begins() = runTest {
    val steps = RecordingEngineSteps().apply { allowResources = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    lifecycle.close()

    assertFalse(lifecycle.beginAttach())

    steps.allowResources.complete(Unit)
    lifecycle.awaitClosed()
    assertEquals(listOf("close resources"), steps.commands)
  }

  @Test
  fun detach_during_failed_engine_creation_cleans_partial_engine_resources() = runTest {
    val steps =
      RecordingEngineSteps().apply {
        allowCreate = CompletableDeferred()
        createFailure = TestFailure("create")
      }
    val lifecycle = lifecycleOf(steps)
    val attaching = async { runCatching { lifecycle.attach() } }
    steps.createStarted.await()
    val detaching = async { lifecycle.detach() }
    runCurrent()

    steps.allowCreate.complete(Unit)

    assertTrue(detaching.await())
    assertTrue(attaching.await().exceptionOrNull() is MapLeaseInvalidatedException)
    // The detachment still detaches the lease whose attachment began, then destroys the engine.
    val engine = steps.commands.first().removePrefix("create ")
    assertEquals(
      listOf("create $engine", "detach $engine ${steps.lastLease}", "destroy $engine"),
      steps.commands,
    )
  }

  @Test
  fun failed_detached_engine_creation_cleans_partial_engine_resources() = runTest {
    val steps = RecordingEngineSteps().apply { createFailure = TestFailure("create") }
    val lifecycle = lifecycleOf(steps)

    assertFailsWith<TestFailure> { lifecycle.ensureEngine() }

    assertEquals(1, steps.commands.count { it.startsWith("destroy ") })
    assertNull(lifecycle.engine)
  }

  @Test
  fun an_attachment_that_ends_before_it_starts_leaves_engine_creation_to_the_next_one() = runTest {
    val steps =
      RecordingEngineSteps().apply {
        allowCreate = CompletableDeferred()
        allowDetach = CompletableDeferred()
        createFailure = TestFailure("create")
      }
    val lifecycle = lifecycleOf(steps)
    val failedCreation = async { runCatching { lifecycle.attach() } }
    steps.createStarted.await()
    val failedDetach = async { lifecycle.detach() }
    runCurrent()
    steps.allowCreate.complete(Unit)
    runCurrent()
    steps.createFailure = null
    // Queued behind the detachment still running, this attachment is to create the engine.
    assertTrue(lifecycle.beginAttach())
    val ended = async { lifecycle.detach() }
    runCurrent()

    val next = async { lifecycle.attach() }
    runCurrent()
    steps.allowDetach.complete(Unit)
    next.await()

    assertIs<MapLeaseInvalidatedException>(failedCreation.await().exceptionOrNull())
    assertTrue(failedDetach.await())
    assertTrue(ended.await())
    val engine = checkNotNull(lifecycle.engine)
    assertEquals(
      listOf("create $engine", "attach $engine ${lifecycle.lease}"),
      steps.commands.takeLast(2),
    )
  }

  @Test
  fun detached_engine_access_waits_for_an_attachment_in_progress_and_fails_with_it() = runTest {
    val steps =
      RecordingEngineSteps().apply {
        allowAttach = CompletableDeferred()
        attachFailure = TestFailure("attach")
      }
    val lifecycle = lifecycleOf(steps)
    val attaching = async { runCatching { lifecycle.attach() } }
    steps.attachStarted.await()
    val access = async { runCatching { lifecycle.ensureEngine() } }
    runCurrent()
    assertFalse(access.isCompleted)

    steps.allowAttach.complete(Unit)

    assertIs<TestFailure>(access.await().exceptionOrNull())
    assertIs<TestFailure>(attaching.await().exceptionOrNull())
    assertEquals(checkNotNull(lifecycle.engine), lifecycle.ensureEngine())
  }

  @Test
  fun detached_engine_access_survives_an_interrupted_presentation_attach() = runTest {
    val steps = RecordingEngineSteps().apply { allowAttach = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    val attaching = async { runCatching { lifecycle.attach() } }
    steps.attachStarted.await()
    val engine = checkNotNull(lifecycle.engine)
    val access = async { lifecycle.ensureEngine() }
    val detaching = async { lifecycle.detach() }
    runCurrent()

    steps.allowAttach.complete(Unit)

    assertTrue(detaching.await())
    assertIs<MapLeaseInvalidatedException>(attaching.await().exceptionOrNull())
    assertEquals(engine, access.await())
  }

  @Test
  fun closing_after_detached_engine_access_starts_cancels_the_access() = runTest {
    val steps = RecordingEngineSteps().apply { allowCreate = CompletableDeferred() }
    val lifecycle = lifecycleOf(steps)
    val access = async { lifecycle.ensureEngine() }
    steps.createStarted.await()

    lifecycle.close()
    steps.allowCreate.complete(Unit)

    assertFailsWith<CancellationException> { access.await() }
    lifecycle.awaitClosed()
  }

  @Test
  fun closing_during_failed_engine_creation_cancels_with_the_failure_as_cause() = runTest {
    val failure = TestFailure("create")
    val steps =
      RecordingEngineSteps().apply {
        allowCreate = CompletableDeferred()
        createFailure = failure
      }
    val lifecycle = lifecycleOf(steps)
    val access = async { lifecycle.ensureEngine() }
    steps.createStarted.await()

    lifecycle.close()
    steps.allowCreate.complete(Unit)

    val cancellation = assertFailsWith<CancellationException> { access.await() }
    assertTrue(generateSequence(cancellation as Throwable?) { it.cause }.any { it === failure })
    lifecycle.awaitClosed()
  }
}

private class FatalLifecycleError : Error("fatal lifecycle cleanup failure")

private class RecordingEngineSteps : RetainedEngineSteps {
  val commands = mutableListOf<String>()
  val createStarted = CompletableDeferred<Unit>()
  val attachStarted = CompletableDeferred<Unit>()
  val detachStarted = CompletableDeferred<Unit>()
  var allowCreate = CompletableDeferred(Unit)
  var allowAttach = CompletableDeferred(Unit)
  var allowDetach = CompletableDeferred(Unit)
  var allowResources = CompletableDeferred(Unit)
  var createFailure: Throwable? = null
  var attachFailure: Throwable? = null
  var detachFailure: Throwable? = null
  var destroyFailure: Throwable? = null
  var resourcesFailure: Throwable? = null
  var lastLease: RenderLease? = null

  override suspend fun createEngine(identity: EngineMapIdentity) {
    commands += "create $identity"
    createStarted.complete(Unit)
    allowCreate.await()
    createFailure?.let { throw it }
  }

  override suspend fun attach(identity: EngineMapIdentity, lease: RenderLease) {
    commands += "attach $identity $lease"
    lastLease = lease
    attachStarted.complete(Unit)
    allowAttach.await()
    attachFailure?.let { throw it }
  }

  override suspend fun detach(identity: EngineMapIdentity, lease: RenderLease) {
    commands += "detach $identity $lease"
    lastLease = lease
    detachStarted.complete(Unit)
    allowDetach.await()
    detachFailure?.let { throw it }
  }

  override suspend fun destroyEngine(identity: EngineMapIdentity) {
    commands += "destroy $identity"
    destroyFailure?.let { throw it }
  }

  override suspend fun closeResources() {
    commands += "close resources"
    allowResources.await()
    resourcesFailure?.let { throw it }
  }
}

private class TestFailure(message: String) : RuntimeException(message)
