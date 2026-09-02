package org.maplibre.compose.map

/** Reports one or more nonfatal failures while closing map resources. */
public class MapCleanupException
internal constructor(resourceName: String, failures: List<Throwable>) :
  RuntimeException(
    "$resourceName cleanup failed in ${failures.size} resource(s)",
    failures.firstOrNull(),
  ) {
  /** Every cleanup failure, in cleanup order. */
  public val failures: List<Throwable> = failures.toList()

  init {
    require(this.failures.isNotEmpty()) { "A cleanup exception must contain at least one failure" }
    this.failures.drop(1).forEach(::addSuppressed)
  }
}

internal fun List<Throwable>.cleanupResult(resourceName: String): Result<Unit> =
  when {
    isEmpty() -> Result.success(Unit)
    else ->
      firstOrNull { it is Error }?.let { Result.failure(it) }
        ?: Result.failure(MapCleanupException(resourceName, this))
  }

internal fun MutableList<Throwable>.addCleanupFailure(failure: Throwable) {
  if (failure is MapCleanupException) {
    failure.failures.forEach(::addCleanupFailure)
  } else if (none { it === failure }) {
    add(failure)
  }
}
