package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
class MapLifecycleBindingTest {

  private fun TestScope.bindLifecycle(adapter: MapLifecyclePlatformAdapter): MapLifecycleBinding =
    mapRuntimeForTest(physicalScope = backgroundScope)
      .createMapState(BaseStyle.Demo)
      .lifecycle
      .bind(adapter)

  @Test
  fun a_map_attaches_with_an_engine_identity_and_render_lease() = runTest {
    val adapter = FakeMapLifecycleAdapter()
    val lifecycle = bindLifecycle(adapter)

    assertNull(lifecycle.engineIdentity)
    val lease = lifecycle.attach()

    val engine = lifecycle.engineIdentity
    assertEquals(lease, lifecycle.renderLease)
    assertEquals(listOf("create $engine", "attach $engine $lease"), adapter.commands)
  }

  @Test
  fun a_rival_attachment_fails_without_waiting_or_changing_platform_state() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowAttach = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val first = async { lifecycle.attach() }
    adapter.attachStarted.await()

    assertFailsWith<MapAlreadyAttachedException> { lifecycle.attach() }
    assertEquals(2, adapter.commands.size)

    adapter.allowAttach.complete(Unit)
    first.await()
  }

  @Test
  fun detaching_invalidates_the_lease_before_retained_engine_cleanup_finishes() = runTest {
    val adapter = FakeMapLifecycleAdapter()
    val lifecycle = bindLifecycle(adapter)
    val lease = lifecycle.attach()
    val engine = checkNotNull(lifecycle.engineIdentity)
    adapter.allowDetach = CompletableDeferred()

    val detach = async { lifecycle.detach(lease) }
    adapter.detachStarted.await()

    assertTrue(!lifecycle.acceptPresentationEvent(engine, lease) { error("stale event ran") })
    assertFailsWith<MapAlreadyAttachedException> { lifecycle.attach() }

    adapter.allowDetach.complete(Unit)
    assertTrue(detach.await())
  }

  @Test
  fun attach_failure_cleans_partial_resources_and_returns_to_open_detached() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { attachFailure = TestFailure("attach") }
    val lifecycle = bindLifecycle(adapter)

    assertFailsWith<TestFailure> { lifecycle.attach() }

    val engine = checkNotNull(lifecycle.engineIdentity)
    assertEquals(
      listOf(
        "create $engine",
        "attach $engine ${adapter.lastLease}",
        "detach $engine ${adapter.lastLease}",
      ),
      adapter.commands,
    )
  }

  @Test
  fun destroy_on_detach_policy_destroys_an_engine_after_attach_failure() = runTest {
    val adapter =
      FakeMapLifecycleAdapter().apply {
        retention = EngineRetention.DESTROY
        attachFailure = TestFailure("attach")
      }
    val lifecycle = bindLifecycle(adapter)

    assertFailsWith<TestFailure> { lifecycle.attach() }

    assertEquals(1, adapter.commands.count { it.startsWith("destroy ") })
  }

  @Test
  fun detach_during_attach_invalidates_the_lease_and_cleans_the_partial_attachment() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowAttach = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val attaching = async { runCatching { lifecycle.attach() } }
    adapter.attachStarted.await()
    val lease = checkNotNull(adapter.lastLease)

    val detaching = async { lifecycle.detach(lease) }
    runCurrent()

    adapter.allowAttach.complete(Unit)
    assertTrue(detaching.await())
    assertTrue(attaching.await().exceptionOrNull() is MapLeaseInvalidatedException)
    assertEquals(1, adapter.commands.count { it.startsWith("detach ") })
  }

  @Test
  fun close_commits_immediately_and_repeated_callers_join_one_cleanup() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowDetach = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val lease = lifecycle.attach()
    val engine = checkNotNull(lifecycle.engineIdentity)
    val style = lifecycle.claimStyle(engine)

    lifecycle.close()
    lifecycle.close()

    assertFailsWith<MapClosedException> { lifecycle.attach() }
    assertTrue(!lifecycle.acceptEngineEvent(engine) { error("closed engine event ran") })
    assertTrue(!lifecycle.acceptStyleEvent(engine, style) { error("closed style event ran") })
    assertTrue(!lifecycle.acceptPresentationEvent(engine, lease) { error("closed event ran") })
    adapter.detachStarted.await()
    adapter.allowDetach.complete(Unit)
    lifecycle.awaitClosed()

    assertEquals(1, adapter.commands.count { it.startsWith("detach ") })
    assertEquals(1, adapter.commands.count { it.startsWith("destroy ") })
    assertEquals(1, adapter.commands.count { it == "close resources" })
  }

  @Test
  fun cleanup_attempts_every_resource_and_await_closed_reports_every_failure() = runTest {
    val adapter =
      FakeMapLifecycleAdapter().apply {
        detachFailure = TestFailure("detach")
        destroyFailure = TestFailure("engine")
        resourcesFailure = TestFailure("resources")
      }
    val lifecycle = bindLifecycle(adapter)
    lifecycle.attach()

    lifecycle.close()
    val failure = assertFailsWith<MapCleanupException> { lifecycle.awaitClosed() }

    assertEquals(listOf("detach", "engine", "resources"), failure.failures.map { it.message })
  }

  @Test
  fun cleanup_preserves_a_fatal_error() = runTest {
    val fatal = FatalLifecycleError()
    val lifecycle = bindLifecycle(FakeMapLifecycleAdapter().apply { resourcesFailure = fatal })

    lifecycle.close()
    val reported = assertFailsWith<FatalLifecycleError> { lifecycle.awaitClosed() }

    assertSame(fatal, reported)
  }

  @Test
  fun cancelling_an_attach_caller_invalidates_the_lease_but_physical_cleanup_continues() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowAttach = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val caller = async { lifecycle.attach() }
    adapter.attachStarted.await()

    caller.cancelAndJoin()
    adapter.allowAttach.complete(Unit)
    runCurrent()

    assertEquals(1, adapter.commands.count { it.startsWith("detach ") })
  }

  @Test
  fun durable_engine_and_current_style_events_are_accepted_while_native_is_detached() = runTest {
    val adapter = FakeMapLifecycleAdapter()
    val lifecycle = bindLifecycle(adapter)
    val lease = lifecycle.attach()
    val engine = checkNotNull(lifecycle.engineIdentity)
    val firstStyle = lifecycle.claimStyle(engine)
    val currentStyle = lifecycle.claimStyle(engine)
    lifecycle.detach(lease)
    val accepted = mutableListOf<String>()

    assertTrue(lifecycle.acceptEngineEvent(engine) { accepted += "engine" })
    assertTrue(!lifecycle.acceptStyleEvent(engine, firstStyle) { accepted += "stale style" })
    assertTrue(lifecycle.acceptStyleEvent(engine, currentStyle) { accepted += "style" })
    assertTrue(!lifecycle.acceptPresentationEvent(engine, lease) { accepted += "presentation" })

    assertEquals(listOf("engine", "style"), accepted)
  }

  @Test
  fun superseded_style_request_events_are_rejected() = runTest {
    val adapter = FakeMapLifecycleAdapter()
    val lifecycle = bindLifecycle(adapter)
    lifecycle.attach()
    val engine = checkNotNull(lifecycle.engineIdentity)
    val superseded = checkNotNull(lifecycle.claimStyleRequestIdentity(engine))
    val current = checkNotNull(lifecycle.claimStyleRequestIdentity(engine))
    val accepted = mutableListOf<String>()

    assertTrue(!lifecycle.acceptStyleRequestEvent(engine, superseded) { accepted += "superseded" })
    assertTrue(lifecycle.acceptStyleRequestEvent(engine, current) { accepted += "current" })
    assertEquals(listOf("current"), accepted)
  }

  @Test
  fun close_joins_a_failing_detach_and_still_attempts_engine_and_resource_cleanup() = runTest {
    val adapter =
      FakeMapLifecycleAdapter().apply {
        allowDetach = CompletableDeferred()
        detachFailure = TestFailure("detach")
      }
    val lifecycle = bindLifecycle(adapter)
    val lease = lifecycle.attach()
    val detaching = async { runCatching { lifecycle.detach(lease) } }
    adapter.detachStarted.await()

    lifecycle.close()
    adapter.allowDetach.complete(Unit)
    val failure = assertFailsWith<MapCleanupException> { lifecycle.awaitClosed() }

    assertEquals(listOf("detach"), failure.failures.map { it.message })
    assertTrue(detaching.await().isFailure)
    assertEquals(1, adapter.commands.count { it.startsWith("destroy ") })
    assertTrue("close resources" in adapter.commands)
  }

  @Test
  fun destroying_detach_invalidates_engine_and_style_identities_before_reattachment() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { retention = EngineRetention.DESTROY }
    val lifecycle = bindLifecycle(adapter)
    val firstLease = lifecycle.attach()
    val firstEngine = checkNotNull(lifecycle.engineIdentity)
    val firstStyle = lifecycle.claimStyle(firstEngine)

    lifecycle.detach(firstLease)

    assertTrue(!lifecycle.acceptEngineEvent(firstEngine) { error("destroyed engine event ran") })
    assertTrue(
      !lifecycle.acceptStyleEvent(firstEngine, firstStyle) { error("destroyed style event ran") }
    )

    lifecycle.attach()
    assertTrue(lifecycle.engineIdentity != firstEngine)
  }

  @Test
  fun destroy_on_detach_engine_replacement_keeps_the_lease_but_changes_engine_identity() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { retention = EngineRetention.DESTROY }
    val lifecycle = bindLifecycle(adapter)
    val lease = lifecycle.attach()
    val departedEngine = checkNotNull(lifecycle.engineIdentity)
    val departedStyle = lifecycle.claimStyle(departedEngine)

    assertTrue(lifecycle.beginEngineReplacement(departedEngine, lease))

    assertEquals(lease, lifecycle.renderLease)
    assertTrue(lifecycle.engineIdentity != departedEngine)
    assertTrue(!lifecycle.acceptEngineEvent(departedEngine) { error("departed engine event ran") })
    assertTrue(
      !lifecycle.acceptStyleEvent(departedEngine, departedStyle) {
        error("departed style event ran")
      }
    )
  }

  @Test
  fun a_departed_lease_cannot_detach_a_later_presentation() = runTest {
    val adapter = FakeMapLifecycleAdapter()
    val lifecycle = bindLifecycle(adapter)
    val departed = lifecycle.attach()
    lifecycle.detach(departed)
    val current = lifecycle.attach()
    val commandsBeforeStaleDetach = adapter.commands.toList()

    assertTrue(!lifecycle.detach(departed))

    assertEquals(current, lifecycle.renderLease)
    assertEquals(commandsBeforeStaleDetach, adapter.commands)
  }

  @Test
  fun close_during_attach_invalidates_the_lease_and_joins_its_cleanup() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowAttach = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val attaching = async { runCatching { lifecycle.attach() } }
    adapter.attachStarted.await()

    lifecycle.close()
    adapter.allowAttach.complete(Unit)
    lifecycle.awaitClosed()

    assertTrue(attaching.await().exceptionOrNull() is MapLeaseInvalidatedException)
    assertEquals(1, adapter.commands.count { it.startsWith("detach ") })
    assertEquals(1, adapter.commands.count { it.startsWith("destroy ") })
  }

  @Test
  fun closing_a_never_attached_map_waits_for_shared_cleanup() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowResources = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)

    lifecycle.close()
    val closed = async { lifecycle.awaitClosed() }
    runCurrent()
    assertFalse(closed.isCompleted)

    adapter.allowResources.complete(Unit)
    closed.await()
    assertEquals(listOf("close resources"), adapter.commands)
  }

  @Test
  fun a_late_attach_start_is_inert_after_closure_begins() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowResources = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    lifecycle.close()

    assertTrue(!lifecycle.beginAttachIfOpen())

    adapter.allowResources.complete(Unit)
    lifecycle.awaitClosed()
    assertEquals(listOf("close resources"), adapter.commands)
  }

  @Test
  fun detach_during_failed_engine_creation_cleans_partial_engine_resources() = runTest {
    val adapter =
      FakeMapLifecycleAdapter().apply {
        allowCreate = CompletableDeferred()
        createFailure = TestFailure("create")
      }
    val lifecycle = bindLifecycle(adapter)
    val attaching = lifecycle.beginAttach()
    adapter.createStarted.await()
    val detaching = async { lifecycle.detach(attaching.lease) }
    runCurrent()

    adapter.allowCreate.complete(Unit)

    assertTrue(detaching.await())
    assertTrue(attaching.completion.await().exceptionOrNull() is MapLeaseInvalidatedException)
    assertEquals(1, adapter.commands.count { it.startsWith("destroy ") })
  }

  @Test
  fun failed_detached_engine_creation_cleans_partial_engine_resources() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { createFailure = TestFailure("create") }
    val lifecycle = bindLifecycle(adapter)

    assertFailsWith<TestFailure> { lifecycle.ensureEngine() }

    assertEquals(1, adapter.commands.count { it.startsWith("destroy ") })
  }

  @Test
  fun detached_engine_access_survives_an_interrupted_presentation_attach() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowAttach = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val attaching = async { runCatching { lifecycle.attach() } }
    adapter.attachStarted.await()
    val lease = checkNotNull(adapter.lastLease)
    val engine = checkNotNull(lifecycle.engineIdentity)
    val access = async { lifecycle.ensureEngine() }
    val detaching = async { lifecycle.detach(lease) }
    runCurrent()

    adapter.allowAttach.complete(Unit)

    assertTrue(detaching.await())
    assertIs<MapLeaseInvalidatedException>(attaching.await().exceptionOrNull())
    assertEquals(engine, access.await())
  }

  @Test
  fun closing_after_detached_engine_access_starts_cancels_the_access() = runTest {
    val adapter = FakeMapLifecycleAdapter().apply { allowCreate = CompletableDeferred() }
    val lifecycle = bindLifecycle(adapter)
    val access = async { lifecycle.ensureEngine() }
    adapter.createStarted.await()

    lifecycle.close()
    adapter.allowCreate.complete(Unit)

    assertFailsWith<CancellationException> { access.await() }
    lifecycle.awaitClosed()
  }

  @Test
  fun closing_during_failed_engine_creation_cancels_with_the_failure_as_cause() = runTest {
    val failure = TestFailure("create")
    val adapter =
      FakeMapLifecycleAdapter().apply {
        allowCreate = CompletableDeferred()
        createFailure = failure
      }
    val lifecycle = bindLifecycle(adapter)
    val access = async { lifecycle.ensureEngine() }
    adapter.createStarted.await()

    lifecycle.close()
    adapter.allowCreate.complete(Unit)

    val cancellation = assertFailsWith<CancellationException> { access.await() }
    assertTrue(generateSequence(cancellation as Throwable?) { it.cause }.any { it === failure })
    lifecycle.awaitClosed()
  }
}

private class FatalLifecycleError : Error("fatal lifecycle cleanup failure")

private class FakeMapLifecycleAdapter : MapLifecyclePlatformAdapter {
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

  var retention = EngineRetention.RETAIN

  override val engineRetention: EngineRetention
    get() = retention

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

private fun MapLifecycleBinding.claimStyle(engine: EngineMapIdentity): StyleIdentity {
  val request = checkNotNull(claimStyleRequestIdentity(engine))
  return checkNotNull(claimStyleIdentity(engine, request) {})
}
