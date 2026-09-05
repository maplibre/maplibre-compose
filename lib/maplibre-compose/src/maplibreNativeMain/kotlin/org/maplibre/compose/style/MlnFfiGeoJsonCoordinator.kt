package org.maplibre.compose.style

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.util.rethrowIfFatal

/** One source installation's latest submission, with at most one active and one pending parse. */
internal class MlnFfiGeoJsonCoordinator<P : AutoCloseable>(
  private val prepare: (GeoJsonData) -> P,
  private val install: (P, isCurrent: () -> Boolean) -> Unit,
  private val reportFailure: (Throwable, isCurrent: () -> Boolean) -> Unit,
  private val synchronousUpdate: Boolean = false,
  dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : AutoCloseable {
  private class Request {
    val completion = CompletableDeferred<Result<Unit>>()
  }

  private data class Work(val request: Request, val data: GeoJsonData)

  private val lock = MlnFfiLock()
  private val scope = if (synchronousUpdate) null else CoroutineScope(SupervisorJob() + dispatcher)
  private val pending = Channel<Work>(Channel.CONFLATED)
  private var latest: Request? = null
  private var closed = false

  init {
    val worker = scope?.launch { for (work in pending) prepareAndInstall(work) }
    worker?.invokeOnCompletion { error ->
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

  private fun prepareAndInstall(work: Work) {
    val (request, data) = work
    var result = Result.success(Unit)
    try {
      if (isCurrent(request)) {
        prepare(data).use { prepared ->
          if (isCurrent(request)) install(prepared) { isCurrent(request) }
        }
      }
    } catch (error: Throwable) {
      result = Result.failure(error)
      if (synchronousUpdate || error is CancellationException) throw error
      rethrowIfFatal(error)
      reportFailure(error) { isCurrent(request) }
    } finally {
      request.completion.complete(result)
    }
  }

  /** Called in owner-thread submission order. [installUrl] also runs on that thread. */
  fun submit(data: GeoJsonData, installUrl: (String) -> Unit) {
    val request = Request()
    val previous = lock.withLock {
      check(!closed) { "GeoJSON source installation is closed" }
      val previous = latest
      latest = request
      if (data is GeoJsonData.Uri) pending.tryReceive()
      else if (!synchronousUpdate) pending.trySend(Work(request, data)).getOrThrow()
      previous
    }
    try {
      if (data is GeoJsonData.Uri) {
        try {
          installUrl(data.uri)
          request.completion.complete(Result.success(Unit))
        } catch (error: Throwable) {
          request.completion.complete(Result.failure(error))
          throw error
        }
      } else if (synchronousUpdate) prepareAndInstall(Work(request, data))
    } finally {
      previous?.completion?.complete(Result.success(Unit))
    }
  }

  /** Snapshot capture waits for asynchronous work and receives its latest preparation failure. */
  suspend fun awaitLatest() {
    // Synchronous work finishes on the owner thread and delivers failures to the submitter.
    if (synchronousUpdate) return
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

  override fun close() {
    val request = lock.withLock {
      closed = true
      latest.also { latest = null }
    }
    pending.cancel()
    scope?.cancel()
    request?.completion?.complete(Result.success(Unit))
  }
}
