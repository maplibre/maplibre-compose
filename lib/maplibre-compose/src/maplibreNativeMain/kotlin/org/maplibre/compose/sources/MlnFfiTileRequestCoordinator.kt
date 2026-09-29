package org.maplibre.compose.sources

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.maplibre.compose.mlnffi.MlnFfiLock
import org.maplibre.compose.mlnffi.withLock
import org.maplibre.compose.style.MlnFfiStyleBinding
import org.maplibre.compose.util.rethrowIfFatal
import org.maplibre.nativeffi.geo.CanonicalTileId
import org.maplibre.nativeffi.map.MapHandle

/**
 * Runs cancellable tile requests for one custom source of one loaded style, and delivers only the
 * current request for each tile, until [close].
 */
internal class MlnFfiTileRequestCoordinator<T>(
  name: String,
  private val binding: MlnFfiStyleBinding,
  private val load: suspend (TileCoordinate) -> T,
  private val deliver: (MapHandle, CanonicalTileId, T) -> Unit,
  private val fail: (MapHandle, CanonicalTileId, Throwable) -> Unit,
) : AutoCloseable {
  private class Request(val token: Long, val job: Job)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(name))
  private val lock = MlnFfiLock()
  private var closed = false
  private var nextToken = 0L
  private val requests = mutableMapOf<CanonicalTileId, Request>()

  fun fetch(tileId: CanonicalTileId) {
    val coordinate = tileId.toTileCoordinate()
    var previous: Job? = null
    val job = lock.withLock {
      if (closed) return
      val token = ++nextToken
      val launched =
        scope.launch(start = CoroutineStart.LAZY) {
          val result: Result<T> =
            try {
              Result.success(load(coordinate))
            } catch (error: CancellationException) {
              forget(tileId, token)
              throw error
            } catch (error: Throwable) {
              rethrowIfFatal(error)
              Result.failure(error)
            }
          answer(tileId, token, result)
        }
      previous = requests.put(tileId, Request(token, launched))?.job
      launched
    }
    previous?.cancel()
    job.start()
  }

  fun cancel(tileId: CanonicalTileId) {
    lock.withLock { requests.remove(tileId) }?.job?.cancel()
  }

  /** Cancels every request and ignores later fetches. An answer already submitted is dropped. */
  override fun close() {
    lock.withLock {
      closed = true
      requests.clear()
    }
    scope.cancel()
  }

  private fun answer(tileId: CanonicalTileId, token: Long, result: Result<T>) {
    // Submitted rather than awaited: the worker has nothing left to do with the answer.
    binding.submit(onDropped = { forget(tileId, token) }) { map ->
      if (!forget(tileId, token)) return@submit
      result.fold(
        onSuccess = { deliver(map, tileId, it) },
        onFailure = { fail(map, tileId, it) },
      )
    }
  }

  /** Forgets the request for [tileId] if it is still the one [token] names. */
  private fun forget(tileId: CanonicalTileId, token: Long): Boolean = lock.withLock {
    if (requests[tileId]?.token != token) {
      false
    } else {
      requests.remove(tileId)
      true
    }
  }
}

internal fun CanonicalTileId.toTileCoordinate(): TileCoordinate =
  TileCoordinate(zoomLevel = z, x = x, y = y)

internal fun TileCoordinate.toMlnFfiTileId(): CanonicalTileId =
  CanonicalTileId(z = zoomLevel, x = x, y = y)
