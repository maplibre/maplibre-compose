@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.runMapTest
import org.maplibre.nativeffi.ResourceErrorReason
import org.maplibre.nativeffi.ResourceKind
import org.maplibre.nativeffi.ResourceLoadingMethod
import org.maplibre.nativeffi.ResourcePriority
import org.maplibre.nativeffi.ResourceRange
import org.maplibre.nativeffi.ResourceRequestInfo
import org.maplibre.nativeffi.ResourceResponse
import org.maplibre.nativeffi.ResourceResponseStatus
import org.maplibre.nativeffi.ResourceStoragePolicy
import org.maplibre.nativeffi.ResourceUsage

/**
 * How long a read or load held open by a test waits to be released, so that a failed test does not
 * strand the worker it holds.
 */
private const val HoldMillis = 10_000L

/**
 * What the provider does with a request between taking it and answering it. Taking a request must
 * not read it: the callback arrives on a MapLibre network thread holding a lease that
 * `RuntimeHandle.close()` spin-waits on.
 *
 * Driven through [MlnFfiResourceProvider.take] with a stand-in request, because the binding hands
 * out `ResourceRequestHandle` only from inside its own callback and the type is final.
 */
class MlnFfiResourceRequestTest {

  private val reads = RecordingList<String>()
  private val providers = mutableListOf<MlnFfiResourceProvider>()

  @AfterTest
  fun cleanUp() {
    providers.forEach { it.close() }
  }

  private fun provider(read: (String, String) -> ResourceResponse) =
    MlnFfiResourceProvider(
        getLogger = { null },
        read = { url, requestedUrl ->
          reads += url
          read(url, requestedUrl)
        },
      )
      .also { providers += it }

  @Test
  fun taking_a_request_returns_before_the_resource_is_read() = runTest {
    val reading = CompletableDeferred<Unit>()
    val finishRead = TestLatch(1)
    val provider = provider { _, _ ->
      reading.complete(Unit)
      finishRead.await(HoldMillis)
      ok("late")
    }
    val request = RecordedRequest()

    provider.take(request, Url, Url)

    reading.await()
    assertEquals(0, request.completions, "the request was answered before the read finished")
    finishRead.countDown()
    request.awaitAnswer()
    assertEquals("late", request.response.bytes.decodeToString())
    request.awaitClose()
  }

  @Test
  fun a_user_scope_cancelled_before_dispatch_still_closes_the_handle() = runTest {
    val provider =
      MlnFfiResourceProvider(
          getLogger = { null },
          userCoroutineScope = CoroutineScope(SupervisorJob().apply { cancel() }),
        )
        .also { providers += it }
    val userProvider =
      MapResourceProvider(accepts = { true }, load = { MapResourceLoad.Bytes(ByteArray(0)) })
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(Url, MapResourceKind.Style), userProvider)
    request.awaitClose()
    assertEquals(1, request.closes)
    assertEquals(1, request.completions)
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
  }

  @Test
  fun close_does_not_cancel_caller_user_scope() {
    val callerScope = CoroutineScope(SupervisorJob())
    val provider =
      MlnFfiResourceProvider(
        getLogger = { null },
        userCoroutineScope = callerScope,
      )
    provider.close()
    assertTrue(callerScope.isActive)
  }

  @Test
  fun take_user_after_shutdown_is_refused() {
    val provider =
      MlnFfiResourceProvider(getLogger = { null }).also {
        providers += it
      }
    val userProvider =
      MapResourceProvider(accepts = { true }, load = { MapResourceLoad.Bytes(ByteArray(0)) })
    provider.close()
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(Url, MapResourceKind.Style), userProvider)
    assertEquals(1, request.completions)
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
    assertContains(request.response.errorMessage.orEmpty(), "shut down")
    assertEquals(1, request.closes)
  }

  @Test
  fun a_cancelled_user_load_is_cancelled_and_closed() = runTest {
    val loading = CompletableDeferred<Unit>()
    val cancelled = AtomicBoolean(false)
    val provider =
      MlnFfiResourceProvider(getLogger = { null }).also {
        providers += it
      }
    val userProvider =
      MapResourceProvider(
        accepts = { true },
        load = {
          suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelled.store(true) }
            loading.complete(Unit)
          }
        },
      )
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(Url, MapResourceKind.Style), userProvider)
    loading.await()

    request.cancel()
    request.awaitClose()

    assertTrue(cancelled.load(), "an abandoned request must cancel provider.load")
    assertEquals(0, request.completions)
  }

  @Test
  fun replacing_a_style_cancels_the_native_request_and_its_user_load() = runMapTest {
    val loading = AtomicBoolean(false)
    val cancelled = AtomicBoolean(false)
    val userProvider =
      MapResourceProvider(
        accepts = { it.url.startsWith("app://pending-tile/") },
        load = {
          suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelled.store(true) }
            loading.store(true)
          }
        },
      )
    BridgeMapFixture.create(resourceConfig = MapResourceConfig(provider = userProvider)).use {
      it.loadStyle(
        BaseStyle.Json(
          """{"version":8,"sources":{"tiles":{"type":"raster",
          "tiles":["app://pending-tile/{z}/{x}/{y}"],"tileSize":256}},
          "layers":[{"id":"raster","type":"raster","source":"tiles"}]}""",
        ),
      )
      it.awaitUntil("the native request to start provider.load") { loading.load() }
      it.session.setBaseStyle(BaseStyle.Empty)
      it.awaitUntil("style replacement to cancel provider.load") { cancelled.load() }
    }
  }

  @Test
  fun cancellation_during_registration_does_not_start_the_user_load() = runTest {
    val provider = MlnFfiResourceProvider(getLogger = { null }).also { providers += it }
    val loaded = AtomicBoolean(false)
    val userProvider =
      MapResourceProvider(
        accepts = { true },
        load = {
          loaded.store(true)
          MapResourceLoad.NoContent()
        },
      )
    val request = RecordedRequest(cancelOnRegistration = true)

    provider.takeUser(request, MapResourceLoadRequest(Url, MapResourceKind.Style), userProvider)
    request.awaitClose()

    assertEquals(false, loaded.load(), "a request cancelled before loading must not start work")
    assertEquals(0, request.completions)
  }

  @Test
  fun blocking_user_loads_leave_default_dispatcher_work_running() = runTest {
    val provider = MlnFfiResourceProvider(getLogger = { null }).also { providers += it }
    // More than a pool sized to the CPU count, such as Dispatchers.Default, runs at once.
    val loads = 32
    val started = AtomicInt(0)
    val allStarted = CompletableDeferred<Unit>()
    val release = TestLatch(1)
    val userProvider =
      MapResourceProvider(
        accepts = { true },
        load = {
          if (started.incrementAndFetch() == loads) allStarted.complete(Unit)
          // Blocks its thread, as an application's blocking read would.
          release.await(HoldMillis)
          MapResourceLoad.NoContent()
        },
      )
    val requests = List(loads) { RecordedRequest() }
    try {
      requests.forEach {
        provider.takeUser(it, MapResourceLoadRequest(Url, MapResourceKind.Tile), userProvider)
      }
      allStarted.await()
      val defaultWork = CompletableDeferred<Unit>()
      CoroutineScope(Dispatchers.Default).launch { defaultWork.complete(Unit) }
      defaultWork.await()
    } finally {
      release.countDown()
    }
    requests.forEach { it.awaitClose() }
  }

  @Test
  fun a_provider_timeout_completes_an_active_request() = runTest {
    val provider =
      MlnFfiResourceProvider(getLogger = { null }).also {
        providers += it
      }
    val userProvider =
      MapResourceProvider(
        accepts = { true },
        load = { throw CancellationException("timeout") },
      )
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(Url, MapResourceKind.Style), userProvider)
    request.awaitAnswer()
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
    assertContains(request.response.errorMessage.orEmpty(), "cancelled")
    request.awaitClose()
  }

  @Test
  fun bytes_complete_as_ok_with_validators() {
    val response =
      MapResourceLoad.Bytes(
          "body".encodeToByteArray(),
          etag = "v1",
          mustRevalidate = true,
          modified = Instant.fromEpochMilliseconds(10),
          expires = Instant.fromEpochMilliseconds(20),
        )
        .toResourceResponse()
    assertEquals(ResourceResponseStatus.OK, response.status)
    assertEquals("body", response.bytes.decodeToString())
    assertEquals("v1", response.etag)
    assertTrue(response.mustRevalidate)
    assertEquals(10, response.modifiedUnixMs)
    assertEquals(20, response.expiresUnixMs)
  }

  @Test
  fun no_content_and_not_modified_complete_with_their_status() {
    val noContent =
      MapResourceLoad.NoContent(expires = Instant.fromEpochMilliseconds(20)).toResourceResponse()
    assertEquals(ResourceResponseStatus.NO_CONTENT, noContent.status)
    assertEquals(20, noContent.expiresUnixMs)
    val notModified =
      MapResourceLoad.NotModified(modified = Instant.fromEpochMilliseconds(10)).toResourceResponse()
    assertEquals(ResourceResponseStatus.NOT_MODIFIED, notModified.status)
    assertEquals(10, notModified.modifiedUnixMs)
  }

  @Test
  fun a_failure_keeps_its_reason_and_retry_time() {
    val response =
      MapResourceLoad.Failed(
          MapResourceError.RateLimit,
          "slow down",
          retryAfter = Instant.fromEpochMilliseconds(30),
        )
        .toResourceResponse()
    assertEquals(ResourceResponseStatus.ERROR, response.status)
    assertEquals(ResourceErrorReason.RATE_LIMIT, response.errorReason)
    assertEquals("slow down", response.errorMessage)
    assertEquals(30, response.retryAfterUnixMs)
  }

  @Test
  fun the_ffi_request_copies_into_the_load_request() {
    val ffi =
      ResourceRequestInfo(
        requestedUrl = "maplibre://tiles/1/2/3",
        resolvedUrl = "https://tiles.example.com/1/2/3.pbf",
        kind = ResourceKind.TILE,
        loadingMethod = ResourceLoadingMethod.CACHE_ONLY,
        priority = ResourcePriority.LOW,
        usage = ResourceUsage.OFFLINE,
        storagePolicy = ResourceStoragePolicy.VOLATILE,
        range = ResourceRange(0u, 9u),
        priorModifiedUnixMs = 10,
        priorExpiresUnixMs = 20,
        priorEtag = "v1",
        priorData = "old".encodeToByteArray(),
      )
    val load = ffi.toLoadRequest()
    assertEquals("https://tiles.example.com/1/2/3.pbf", load.url)
    assertEquals("maplibre://tiles/1/2/3", load.requestedUrl)
    assertEquals(MapResourceKind.Tile, load.kind)
    assertEquals(MapResourceLoadRequest.LoadingMethod.CacheOnly, load.loadingMethod)
    assertEquals(MapResourceLoadRequest.Priority.Low, load.priority)
    assertEquals(MapResourceLoadRequest.Usage.Offline, load.usage)
    assertEquals(MapResourceLoadRequest.StoragePolicy.Volatile, load.storagePolicy)
    assertEquals(0L..9L, load.range)
    assertEquals(Instant.fromEpochMilliseconds(10), load.priorModified)
    assertEquals(Instant.fromEpochMilliseconds(20), load.priorExpires)
    assertEquals("v1", load.priorEtag)
    assertEquals("old", load.priorData?.decodeToString())
  }

  @Test
  fun the_load_request_names_the_default_ffi_values() {
    val load =
      ResourceRequestInfo(
          requestedUrl = Url,
          resolvedUrl = Url,
          kind = ResourceKind.STYLE,
          loadingMethod = ResourceLoadingMethod.ALL,
          priority = ResourcePriority.REGULAR,
          usage = ResourceUsage.ONLINE,
          storagePolicy = ResourceStoragePolicy.PERMANENT,
          range = null,
          priorModifiedUnixMs = null,
          priorExpiresUnixMs = null,
          priorEtag = "",
          priorData = ByteArray(0),
        )
        .toLoadRequest()
    assertEquals(MapResourceLoadRequest.LoadingMethod.All, load.loadingMethod)
    assertEquals(MapResourceLoadRequest.Priority.Regular, load.priority)
    assertEquals(MapResourceLoadRequest.Usage.Online, load.usage)
    assertEquals(MapResourceLoadRequest.StoragePolicy.Permanent, load.storagePolicy)
  }

  @Test
  fun the_load_request_keeps_unnamed_values_as_their_number() {
    val load =
      ResourceRequestInfo(
          requestedUrl = Url,
          resolvedUrl = Url,
          kind = ResourceKind.STYLE,
          loadingMethod = ResourceLoadingMethod(9u),
          priority = ResourcePriority(9u),
          usage = ResourceUsage(9u),
          storagePolicy = ResourceStoragePolicy(9u),
          range = null,
          priorModifiedUnixMs = null,
          priorExpiresUnixMs = null,
          priorEtag = "",
          priorData = ByteArray(0),
        )
        .toLoadRequest()
    assertEquals("9", load.loadingMethod.value)
    assertEquals("9", load.priority.value)
    assertEquals("9", load.usage.value)
    assertEquals("9", load.storagePolicy.value)
  }

  @Test
  fun a_user_load_reaches_the_request_as_the_ffi_response() = runTest {
    val provider =
      MlnFfiResourceProvider(getLogger = { null }).also {
        providers += it
      }
    val userProvider =
      MapResourceProvider(accepts = { true }, load = { MapResourceLoad.NoContent() })
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(Url, MapResourceKind.Tile), userProvider)
    request.awaitAnswer()
    assertEquals(ResourceResponseStatus.NO_CONTENT, request.response.status)
    request.awaitClose()
  }

  @Test
  fun a_cancelled_request_is_closed_without_being_read() = runTest {
    val provider = provider { _, _ -> ok("unwanted") }
    val request = RecordedRequest(cancelled = true)

    provider.take(request, Url, Url)
    request.awaitClose()

    assertEquals(emptyList(), reads.toList(), "a cancelled request must not be read")
    assertEquals(0, request.completions)
    assertEquals(1, request.closes, "a cancelled request still owns its handle and must close it")
  }

  @Test
  fun shutdown_returns_while_an_accepted_read_finishes_independently() = runTest {
    val reading = CompletableDeferred<Unit>()
    val finishRead = TestLatch(1)
    val provider = provider { _, _ ->
      reading.complete(Unit)
      finishRead.await(HoldMillis)
      ok("in flight")
    }
    val request = RecordedRequest()
    provider.take(request, Url, Url)
    reading.await()

    provider.close()

    assertEquals(0, request.completions, "close should not wait for reads")
    finishRead.countDown()
    request.awaitAnswer()
    assertEquals(1, request.completions, "the in-flight request must still be answered")
    assertEquals("in flight", request.response.bytes.decodeToString())
    request.awaitClose()
  }

  @Test
  fun accepted_reads_are_independent_and_still_finish_after_shutdown() = runTest {
    val reading = CompletableDeferred<Unit>()
    val finishRead = TestLatch(1)
    val provider = provider { _, _ ->
      reading.complete(Unit)
      finishRead.await(HoldMillis)
      ok("queued")
    }
    val first = RecordedRequest()
    val second = RecordedRequest()
    provider.take(first, Url, Url)
    reading.await()
    provider.take(second, OtherUrl, OtherUrl)

    provider.close()
    finishRead.countDown()

    second.awaitAnswer()
    assertEquals(setOf(Url, OtherUrl), reads.toSet(), "both accepted reads must run")
    assertEquals(1, second.completions, "a request the provider took must be answered")
    second.awaitClose()
  }

  @Test
  fun a_request_taken_after_shutdown_is_refused_rather_than_queued() {
    val provider = provider { _, _ -> ok("never") }
    provider.close()
    val request = RecordedRequest()

    provider.take(request, Url, Url)

    assertEquals(emptyList(), reads.toList(), "a refused request must not be read")
    assertEquals(1, request.completions, "an unanswered request leaves MapLibre waiting for it")
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
    assertEquals(ResourceErrorReason.OTHER, request.response.errorReason)
    assertContains(request.response.errorMessage.orEmpty(), "shut down")
    assertEquals(1, request.closes)
  }

  @Test
  fun a_read_that_throws_still_closes_the_request() = runTest {
    // An exception escaping the reader thread would leave the native request alive until the
    // binding's leak cleaner noticed it.
    val provider = provider { _, _ -> throw IllegalStateException("the disk went away") }
    val request = RecordedRequest()

    provider.take(request, Url, Url)
    request.awaitClose()

    assertEquals(0, request.completions)
    assertEquals(1, request.closes)
  }

  private fun ok(body: String) =
    ResourceResponse(ResourceResponseStatus.OK, bytes = body.encodeToByteArray())

  /** A request the provider can take, recording what it did with it. */
  @OptIn(ExperimentalAtomicApi::class)
  private class RecordedRequest(
    cancelled: Boolean = false,
    private val cancelOnRegistration: Boolean = false,
  ) : TakenResourceRequest {
    private val responses = RecordingList<ResourceResponse>()
    private val answered = CompletableDeferred<Unit>()
    private val closed = CompletableDeferred<Unit>()
    private val closeCount = AtomicInt(0)
    private val cancelledState = AtomicBoolean(cancelled)
    private val cancelCallback = AtomicReference<(() -> Unit)?>(null)

    override fun isCancelled(): Boolean = cancelledState.load()

    override fun setCancelCallback(callback: () -> Unit) {
      cancelCallback.store(callback)
      if (cancelOnRegistration) cancelledState.store(true)
      if (cancelledState.load()) cancelCallback.exchange(null)?.invoke()
    }

    fun cancel() {
      cancelledState.store(true)
      cancelCallback.exchange(null)?.invoke()
    }

    override fun complete(response: ResourceResponse) {
      responses += response
      answered.complete(Unit)
    }

    override fun close() {
      cancelCallback.store(null)
      closeCount.incrementAndFetch()
      closed.complete(Unit)
    }

    val completions: Int
      get() = responses.size

    val closes: Int
      get() = closeCount.load()

    val response: ResourceResponse
      get() = responses.single()

    suspend fun awaitAnswer() {
      answered.await()
    }

    suspend fun awaitClose() {
      closed.await()
      assertEquals(1, closes, "the request was closed more than once")
    }
  }

  private companion object {
    const val Url = "jar:file:/demo%20app.jar!/style.json"
    const val OtherUrl = "file:/demo/sprite.png"
  }
}
