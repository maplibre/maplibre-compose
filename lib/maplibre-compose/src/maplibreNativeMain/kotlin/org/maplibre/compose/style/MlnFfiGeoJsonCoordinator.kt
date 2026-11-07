package org.maplibre.compose.style

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.spatialk.geojson.toJson

/**
 * Orders the data submissions of one GeoJSON source installation.
 *
 * MapLibre prepares JSON data in the background, and newer data or a URL replaces data that waits.
 * Features first serialize to JSON on a worker, so this keeps an older serialization from
 * overtaking a later submission: only the latest pending Features serialize, and a result that a
 * newer submission superseded is dropped. JSON text and URLs go to [install] at once.
 *
 * [install] and [reportFailure] run on main. [install] receives only JSON text or a URL and returns
 * the outcome of giving it to the source. [reportFailure] receives each failure of data that no
 * newer submission replaced, with whether this installation still serves the source.
 */
internal class MlnFfiGeoJsonCoordinator(
  private val install: (GeoJsonData) -> Deferred<Unit>,
  private val reportFailure: (Throwable, isCurrent: Boolean) -> Unit,
  private val mainDispatcher: CoroutineDispatcher,
  workerDispatcher: CoroutineDispatcher = Dispatchers.Default,
  private val serialize: (GeoJsonData.Features) -> String = { it.geoJson.toJson() },
) : AutoCloseable {
  private class Request {
    val completion = CompletableDeferred<Result<Unit>>()

    /** Whether a newer submission replaced this one. Under [lock]. */
    var superseded = false
  }

  private data class Work(val request: Request, val data: GeoJsonData.Features)

  private val lock = MlnFfiLock()
  private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)

  // Not cancelled at close: native answers every command, and a removed source still reports a
  // rejected update.
  private val outcomes = CoroutineScope(SupervisorJob() + mainDispatcher)
  private val pending = Channel<Work>(Channel.CONFLATED)
  private var latest: Request? = null
  private var closed = false

  init {
    val worker = scope.launch(workerDispatcher) { for (work in pending) serializeAndInstall(work) }
    worker.invokeOnCompletion { error ->
      if (error != null) {
        val request = lock.withLock {
          closed = true
          latest
        }
        request?.completion?.complete(Result.failure(error))
        pending.cancel()
        scope.cancel()
      }
    }
  }

  private suspend fun serializeAndInstall(work: Work) {
    val (request, data) = work
    if (!isCurrent(request)) return
    val json =
      try {
        serialize(data)
      } catch (error: Throwable) {
        if (error is CancellationException) {
          request.completion.complete(Result.failure(error))
          throw error
        }
        rethrowIfFatal(error)
        withContext(mainDispatcher) { report(request, error) }
        request.completion.complete(Result.failure(error))
        return
      }
    withContext(mainDispatcher) {
      if (isCurrent(request)) installNow(request, GeoJsonData.JsonString(json))
    }
  }

  /** Main only. Completes [request] with the outcome of installing [data]. */
  private fun installNow(request: Request, data: GeoJsonData) {
    val outcome =
      try {
        install(data)
      } catch (error: Throwable) {
        request.completion.complete(Result.failure(error))
        throw error
      }
    outcomes.launch {
      val result =
        try {
          outcome.await()
          Result.success(Unit)
        } catch (error: Throwable) {
          if (error is CancellationException) throw error
          report(request, error)
          Result.failure(error)
        }
      request.completion.complete(result)
    }
  }

  /** Main only. */
  private fun report(request: Request, error: Throwable) {
    val superseded = lock.withLock { request.superseded }
    if (!superseded) reportFailure(error, isCurrent(request))
  }

  /** Called on main, in submission order. */
  fun submit(data: GeoJsonData) {
    val request = Request()
    val previous = lock.withLock {
      check(!closed) { "GeoJSON source installation is closed" }
      val previous = latest
      previous?.superseded = true
      latest = request
      if (data is GeoJsonData.Features) pending.trySend(Work(request, data)).getOrThrow()
      else pending.tryReceive()
      previous
    }
    try {
      if (data !is GeoJsonData.Features) installNow(request, data)
    } finally {
      previous?.completion?.complete(Result.success(Unit))
    }
  }

  /** Snapshot capture waits for the latest submission and receives its failure. */
  suspend fun awaitLatest() {
    while (true) {
      val request = lock.withLock { latest } ?: return
      val result = request.completion.await()
      if (lock.withLock { latest === request }) {
        result.getOrThrow()
        return
      }
    }
  }

  private fun isCurrent(request: Request): Boolean = lock.withLock { !closed && latest === request }

  val isClosed: Boolean
    get() = lock.withLock { closed }

  override fun close() {
    val request = lock.withLock {
      closed = true
      latest.also { latest = null }
    }
    pending.cancel()
    scope.cancel()
    request?.completion?.complete(Result.success(Unit))
  }
}
