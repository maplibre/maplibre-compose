package org.maplibre.compose.resource

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.maplibre.nativeffi.resource.ResourceErrorReason
import org.maplibre.nativeffi.resource.ResourceResponse
import org.maplibre.nativeffi.resource.ResourceResponseStatus

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

  private val reads = CopyOnWriteArrayList<String>()
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
    val reading = CountDownLatch(1)
    val finishRead = CountDownLatch(1)
    val provider = provider { _, _ ->
      reading.countDown()
      finishRead.await(WAIT_SECONDS, TimeUnit.SECONDS)
      ok("late")
    }
    val request = RecordedRequest()

    provider.take(request, URL, URL)

    assertTrue(reading.await(WAIT_SECONDS, TimeUnit.SECONDS), "the read never started")
    assertEquals(0, request.completions, "the request was answered before the read finished")
    finishRead.countDown()
    request.awaitAnswer()
    assertEquals("late", request.response.bytes.decodeToString())
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
    val reading = CountDownLatch(1)
    val finishRead = CountDownLatch(1)
    val provider = provider { _, _ ->
      reading.countDown()
      finishRead.await(WAIT_SECONDS, TimeUnit.SECONDS)
      ok("in flight")
    }
    val request = RecordedRequest()
    provider.take(request, URL, URL)
    assertTrue(reading.await(WAIT_SECONDS, TimeUnit.SECONDS), "the read never started")

    val closed = CountDownLatch(1)
    Thread { provider.close().also { closed.countDown() } }.start()

    assertTrue(closed.await(WAIT_SECONDS, TimeUnit.SECONDS), "close should not wait for reads")
    assertEquals(0, request.completions)
    finishRead.countDown()
    request.awaitAnswer()
    assertEquals(1, request.completions, "the in-flight request must still be answered")
    request.awaitClose()
  }

  @Test
  fun accepted_reads_are_independent_and_still_finish_after_shutdown() {
    val reading = CountDownLatch(1)
    val finishRead = CountDownLatch(1)
    val provider = provider { _, _ ->
      reading.countDown()
      finishRead.await(WAIT_SECONDS, TimeUnit.SECONDS)
      ok("queued")
    }
    val first = RecordedRequest()
    val second = RecordedRequest()
    provider.take(first, URL, URL)
    assertTrue(reading.await(WAIT_SECONDS, TimeUnit.SECONDS), "the read never started")
    provider.take(second, OTHER_URL, OTHER_URL)

    val closed = CountDownLatch(1)
    Thread { provider.close().also { closed.countDown() } }.start()
    finishRead.countDown()

    assertTrue(closed.await(WAIT_SECONDS, TimeUnit.SECONDS), "close never returned")
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
    ResourceResponse(ResourceResponseStatus.OK).also { it.bytes = body.toByteArray() }

  /** A request the provider can take, recording what it did with it. */
  private class RecordedRequest(private val cancelled: Boolean = false) : TakenResourceRequest {
    private val responses = CopyOnWriteArrayList<ResourceResponse>()
    private val answered = CountDownLatch(1)
    private val closeCount = AtomicInteger()

    override fun isCancelled(): Boolean = cancelled

    override fun complete(response: ResourceResponse) {
      responses += response
      answered.countDown()
    }

    override fun close() {
      closeCount.incrementAndGet()
    }

    val completions: Int
      get() = responses.size

    val closes: Int
      get() = closeCount.get()

    val response: ResourceResponse
      get() = responses.single()

    fun awaitAnswer() {
      assertTrue(answered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the request was never answered")
    }

    fun awaitClose() {
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
      while (closes == 0 && System.nanoTime() < deadline) Thread.onSpinWait()
      assertEquals(1, closes, "the request was never closed")
    }
  }

  private companion object {
    const val URL = "jar:file:/demo%20app.jar!/style.json"
    const val OTHER_URL = "file:/demo/sprite.png"
  }
}
