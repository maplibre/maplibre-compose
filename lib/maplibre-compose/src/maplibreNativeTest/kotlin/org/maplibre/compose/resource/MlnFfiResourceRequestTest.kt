@file:OptIn(ExperimentalAtomicApi::class)

package org.maplibre.compose.resource

import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.suspendCancellableCoroutine
import org.maplibre.compose.mlnffi.TestLatch
import org.maplibre.compose.mlnffi.launchTestTask
import org.maplibre.compose.mlnffi.parkForTest
import org.maplibre.compose.testing.RecordingList
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.nativeffi.resource.ResourceKind
import org.maplibre.nativeffi.resource.ResourceLoadingMethod
import org.maplibre.nativeffi.resource.ResourcePriority
import org.maplibre.nativeffi.resource.ResourceRequest
import org.maplibre.nativeffi.resource.ResourceResponse
import org.maplibre.nativeffi.resource.ResourceResponseStatus
import org.maplibre.nativeffi.resource.ResourceStoragePolicy
import org.maplibre.nativeffi.resource.ResourceUsage

/** Long enough that a wait failing here means the thing waited for is not going to happen. */
private const val WAIT_SECONDS = 10L

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
  fun taking_a_request_returns_before_the_resource_is_read() {
    val reading = TestLatch(1)
    val finishRead = TestLatch(1)
    val provider = provider { _, _ ->
      reading.countDown()
      finishRead.await(WAIT_SECONDS * 1_000)
      ok("late")
    }
    val request = RecordedRequest()

    provider.take(request, URL, URL)

    assertTrue(reading.await(WAIT_SECONDS * 1_000), "the read never started")
    assertEquals(0, request.completions, "the request was answered before the read finished")
    finishRead.countDown()
    request.awaitAnswer()
    assertEquals("late", request.response.bytes.decodeToString())
    request.awaitClose()
  }

  @Test
  fun a_user_scope_cancelled_before_dispatch_still_closes_the_handle() {
    val provider =
      MlnFfiResourceProvider(
          getLogger = { null },
          passThroughNetwork = true,
          userCoroutineScope = CoroutineScope(SupervisorJob().apply { cancel() }),
        )
        .also { providers += it }
    provider.userProvider =
      MapResourceProvider(accepts = { true }, load = { MapResourceLoad.Bytes(ByteArray(0)) })
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(URL, MapResourceKind.Style))
    request.awaitClose()
    assertEquals(1, request.closes)
    assertEquals(1, request.completions)
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
  }

  @Test
  fun take_user_after_shutdown_is_refused() {
    val provider =
      MlnFfiResourceProvider(getLogger = { null }, passThroughNetwork = true).also {
        providers += it
      }
    provider.userProvider =
      MapResourceProvider(accepts = { true }, load = { MapResourceLoad.Bytes(ByteArray(0)) })
    provider.close()
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(URL, MapResourceKind.Style))
    assertEquals(1, request.completions)
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
    assertContains(request.response.errorMessage.orEmpty(), "shut down")
    assertEquals(1, request.closes)
  }

  @Test
  fun a_cancelled_user_load_is_cancelled_and_closed() {
    val loading = TestLatch(1)
    val cancelled = AtomicBoolean(false)
    val provider =
      MlnFfiResourceProvider(getLogger = { null }, passThroughNetwork = true).also {
        providers += it
      }
    provider.userProvider =
      MapResourceProvider(
        accepts = { true },
        load = {
          suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancelled.store(true) }
            loading.countDown()
          }
        },
      )
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(URL, MapResourceKind.Style))
    assertTrue(loading.await(WAIT_SECONDS * 1_000), "the user load never started")

    request.cancel()
    request.awaitClose()

    assertTrue(cancelled.load(), "an abandoned request must cancel provider.load")
    assertEquals(0, request.completions)
  }

  @Test
  fun a_provider_timeout_completes_an_active_request() {
    val provider =
      MlnFfiResourceProvider(getLogger = { null }, passThroughNetwork = true).also {
        providers += it
      }
    provider.userProvider =
      MapResourceProvider(
        accepts = { true },
        load = { throw CancellationException("timeout") },
      )
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(URL, MapResourceKind.Style))
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
      ResourceRequest(
        requestedUrl = "maplibre://tiles/1/2/3",
        resolvedUrl = "https://tiles.example.com/1/2/3.pbf",
        kind = ResourceKind.TILE,
        loadingMethod = ResourceLoadingMethod.CACHE_ONLY,
        priority = ResourcePriority.LOW,
        usage = ResourceUsage.OFFLINE,
        storagePolicy = ResourceStoragePolicy.VOLATILE,
        range = ResourceRequest.ByteRange(0, 9),
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
  fun a_user_load_reaches_the_request_as_the_ffi_response() {
    val provider =
      MlnFfiResourceProvider(getLogger = { null }, passThroughNetwork = true).also {
        providers += it
      }
    provider.userProvider =
      MapResourceProvider(accepts = { true }, load = { MapResourceLoad.NoContent() })
    val request = RecordedRequest()
    provider.takeUser(request, MapResourceLoadRequest(URL, MapResourceKind.Tile))
    request.awaitAnswer()
    assertEquals(ResourceResponseStatus.NO_CONTENT, request.response.status)
    request.awaitClose()
  }

  @Test
  fun a_cancelled_request_is_closed_without_being_read() {
    val provider = provider { _, _ -> ok("unwanted") }
    val request = RecordedRequest(cancelled = true)

    provider.take(request, URL, URL)
    request.awaitClose()

    assertEquals(emptyList(), reads.toList(), "a cancelled request must not be read")
    assertEquals(0, request.completions)
    assertEquals(1, request.closes, "a cancelled request still owns its handle and must close it")
  }

  @Test
  fun shutdown_returns_while_an_accepted_read_finishes_independently() {
    val reading = TestLatch(1)
    val finishRead = TestLatch(1)
    val provider = provider { _, _ ->
      reading.countDown()
      finishRead.await(WAIT_SECONDS * 1_000)
      ok("in flight")
    }
    val request = RecordedRequest()
    provider.take(request, URL, URL)
    assertTrue(reading.await(WAIT_SECONDS * 1_000), "the read never started")

    val closed = TestLatch(1)
    launchTestTask { provider.close().also { closed.countDown() } }

    assertTrue(closed.await(WAIT_SECONDS * 1_000), "close should not wait for reads")
    assertEquals(0, request.completions)
    finishRead.countDown()
    request.awaitAnswer()
    assertEquals(1, request.completions, "the in-flight request must still be answered")
    request.awaitClose()
  }

  @Test
  fun accepted_reads_are_independent_and_still_finish_after_shutdown() {
    val reading = TestLatch(1)
    val finishRead = TestLatch(1)
    val provider = provider { _, _ ->
      reading.countDown()
      finishRead.await(WAIT_SECONDS * 1_000)
      ok("queued")
    }
    val first = RecordedRequest()
    val second = RecordedRequest()
    provider.take(first, URL, URL)
    assertTrue(reading.await(WAIT_SECONDS * 1_000), "the read never started")
    provider.take(second, OTHER_URL, OTHER_URL)

    val closed = TestLatch(1)
    launchTestTask { provider.close().also { closed.countDown() } }
    finishRead.countDown()

    assertTrue(closed.await(WAIT_SECONDS * 1_000), "close never returned")
    second.awaitAnswer()
    assertEquals(setOf(URL, OTHER_URL), reads.toSet(), "both accepted reads must run")
    assertEquals(1, second.completions, "a request the provider took must be answered")
    second.awaitClose()
  }

  @Test
  fun a_request_taken_after_shutdown_is_refused_rather_than_queued() {
    val provider = provider { _, _ -> ok("never") }
    provider.close()
    val request = RecordedRequest()

    provider.take(request, URL, URL)

    assertEquals(emptyList(), reads.toList(), "a refused request must not be read")
    assertEquals(1, request.completions, "an unanswered request leaves MapLibre waiting for it")
    assertEquals(ResourceResponseStatus.ERROR, request.response.status)
    assertEquals(ResourceErrorReason.OTHER, request.response.errorReason)
    assertContains(request.response.errorMessage.orEmpty(), "shut down")
    assertEquals(1, request.closes)
  }

  @Test
  fun a_read_that_throws_still_closes_the_request() {
    // An exception escaping the reader thread would leave the native request alive until the
    // binding's leak cleaner noticed it.
    val provider = provider { _, _ -> throw IllegalStateException("the disk went away") }
    val request = RecordedRequest()

    provider.take(request, URL, URL)
    request.awaitClose()

    assertEquals(0, request.completions)
    assertEquals(1, request.closes)
  }

  private fun ok(body: String) =
    ResourceResponse(ResourceResponseStatus.OK).also { it.bytes = body.encodeToByteArray() }

  /** A request the provider can take, recording what it did with it. */
  @OptIn(ExperimentalAtomicApi::class)
  private class RecordedRequest(cancelled: Boolean = false) : TakenResourceRequest {
    private val responses = RecordingList<ResourceResponse>()
    private val answered = TestLatch(1)
    private val closeCount = AtomicInt(0)
    private val cancelledState = AtomicBoolean(cancelled)

    override fun isCancelled(): Boolean = cancelledState.load()

    fun cancel() {
      cancelledState.store(true)
    }

    override fun complete(response: ResourceResponse) {
      responses += response
      answered.countDown()
    }

    override fun close() {
      closeCount.incrementAndFetch()
    }

    val completions: Int
      get() = responses.size

    val closes: Int
      get() = closeCount.load()

    val response: ResourceResponse
      get() = responses.single()

    fun awaitAnswer() {
      assertTrue(answered.await(WAIT_SECONDS * 1_000), "the request was never answered")
    }

    fun awaitClose() {
      val deadline = TimeSource.Monotonic.markNow() + WAIT_SECONDS.seconds
      while (closes == 0 && deadline.hasNotPassedNow()) parkForTest(1)
      assertEquals(1, closes, "the request was never closed")
    }
  }

  private companion object {
    const val URL = "jar:file:/demo%20app.jar!/style.json"
    const val OTHER_URL = "file:/demo/sprite.png"
  }
}
