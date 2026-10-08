package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow

/**
 * Preferences for device-heading updates.
 *
 * @property minimumInterval Preferred minimum time between delivered headings. Must not be
 *   negative.
 * @throws IllegalArgumentException if [minimumInterval] is negative.
 */
@Immutable
public data class HeadingRequest internal constructor(public val minimumInterval: Duration) {
  /** Edits [from]; omitted settings inherit. */
  public constructor(
    from: HeadingRequest = Standard,
    block: Builder.() -> Unit,
  ) : this(Builder(from).apply(block))

  private constructor(builder: Builder) : this(builder.minimumInterval)

  init {
    require(!minimumInterval.isNegative()) {
      "minimumInterval must not be negative, was $minimumInterval"
    }
  }

  public class Builder internal constructor(from: HeadingRequest) {
    /** See [HeadingRequest.minimumInterval]. */
    public var minimumInterval: Duration = from.minimumInterval
  }

  public companion object {
    /** A one-second minimum interval. */
    public val Standard: HeadingRequest = HeadingRequest(1.seconds)
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
  public fun updates(request: HeadingRequest = HeadingRequest.Standard): Flow<HeadingMeasurement>
}
