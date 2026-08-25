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

/** Runs cancellable tile requests and delivers only the current request for the current style. */
internal class MlnFfiTileRequestCoordinator<T>(
  private val name: String,
  private val load: suspend (TileCoordinate) -> T,
  private val deliver: (MapHandle, CanonicalTileId, T) -> Unit,
  private val fail: (MapHandle, CanonicalTileId, Throwable) -> Unit,
) {
  private data class Attachment(
    val generation: Long,
    val binding: MlnFfiStyleBinding,
    val scope: CoroutineScope,
  )

  private data class Request(val attachment: Long, val token: Long, val job: Job)

  private val lock = MlnFfiLock()
  private var nextToken = 0L
  private var nextAttachment = 0L
  private var attachment: Attachment? = null
  private val requests = mutableMapOf<CanonicalTileId, Request>()

  fun attach(binding: MlnFfiStyleBinding) {
    detach()
    lock.withLock {
      nextAttachment++
      attachment =
        Attachment(
          generation = nextAttachment,
          binding = binding,
          scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName(name)),
        )
    }
  }

  fun detach() {
    val previous = lock.withLock {
      requests.clear()
      attachment.also { attachment = null }
    }
    previous?.scope?.cancel()
  }

  fun fetch(tileId: CanonicalTileId) {
    val coordinate = tileId.toTileCoordinate()
    var previous: Job? = null
    val job = lock.withLock {
      val current = attachment ?: return
      val token = ++nextToken
      val launched =
        current.scope.launch(start = CoroutineStart.LAZY) {
          val result: Result<T> =
            try {
              Result.success(load(coordinate))
            } catch (error: CancellationException) {
              forget(tileId, current.generation, token)
              throw error
            } catch (error: Throwable) {
              rethrowIfFatal(error)
              Result.failure(error)
            }
          answer(current, tileId, token, result)
        }
      previous = requests.put(tileId, Request(current.generation, token, launched))?.job
      launched
    }
    previous?.cancel()
    job.start()
  }

  fun cancel(tileId: CanonicalTileId) {
    lock.withLock { requests.remove(tileId) }?.job?.cancel()
  }

  private fun answer(
    current: Attachment,
    tileId: CanonicalTileId,
    token: Long,
    result: Result<T>,
  ) {
    current.binding.mutateMap(
      abandon = { forget(tileId, current.generation, token) },
      action = { map ->
        if (!forget(tileId, current.generation, token)) return@mutateMap
        result.fold(
          onSuccess = { deliver(map, tileId, it) },
          onFailure = { fail(map, tileId, it) },
        )
      },
    )
  }

  private fun forget(tileId: CanonicalTileId, generation: Long, token: Long): Boolean =
    lock.withLock {
      val request = requests[tileId]
      if (request?.attachment != generation || request.token != token) {
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
