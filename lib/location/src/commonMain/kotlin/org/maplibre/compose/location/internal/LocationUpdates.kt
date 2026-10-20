package org.maplibre.compose.location.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.maplibre.compose.location.LocationBackendAvailability
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationUnavailableReason

/**
 * Implementation detail shared by the location runtime modules; not supported for application use.
 */
@RequiresOptIn("Internal location implementation API; may change without notice.")
public annotation class InternalMaplibreComposeApi

/**
 * Collects platform updates only for an available backend, otherwise reports its fixed
 * availability.
 */
@InternalMaplibreComposeApi
public fun Flow<LocationEvent>.withBackendAvailability(
  availability: LocationBackendAvailability,
): Flow<LocationEvent> = flow {
  when (availability) {
    LocationBackendAvailability.Available -> emitAll(this@withBackendAvailability)
    LocationBackendAvailability.Unsupported ->
      emit(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
    is LocationBackendAvailability.Misconfigured ->
      throw IllegalStateException("Location backend is misconfigured", availability.cause)
    else -> error("Unknown location backend availability: $availability")
  }
}
