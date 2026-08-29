package org.maplibre.compose.location

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/** Preferences for device-heading updates. */
public data class HeadingRequest(val minimumInterval: Duration = 1.seconds) {
  init {
    require(!minimumInterval.isNegative()) { "minimumInterval must not be negative" }
  }
}

/** Supplies device-heading measurements. */
public interface HeadingProvider {
  /**
   * Returns a cold stream of headings.
   *
   * Each collector starts an independent platform sensor request. Cancelling collection stops that
   * request and unregisters its callbacks.
   */
  public fun updates(request: HeadingRequest = HeadingRequest()): Flow<Heading>
}
