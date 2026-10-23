package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

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
   * Returns a cold stream of heading events.
   *
   * A provider reports conditions that prevent heading delivery as [HeadingEvent.Unavailable]
   * instead of failing the flow. A device without a heading sensor emits
   * [LocationUnavailableReason.Unsupported] and completes.
   *
   * Each collector starts an independent platform sensor request. Cancelling collection stops that
   * request and unregisters its callbacks.
   */
  public fun updates(request: HeadingRequest = HeadingRequest.Standard): Flow<HeadingEvent>
}

/**
 * Events emitted while collecting [HeadingProvider.updates].
 *
 * A custom provider emits [Update] and [Unavailable].
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface HeadingEvent {
  /**
   * A heading measurement delivered by the provider.
   *
   * @property measurement The delivered heading measurement.
   */
  public data class Update(val measurement: HeadingMeasurement) : HeadingEvent

  /**
   * A condition or failure that currently prevents heading delivery.
   *
   * Heading providers report [LocationUnavailableReason.Unsupported] when the device has no heading
   * sensor and [LocationUnavailableReason.TemporarilyUnavailable] when the sensor cannot determine
   * a heading now, such as near strong magnetic interference.
   *
   * @property reason Portable classification of the condition, or `null` when the provider has no
   *   classification for it, such as an unexpected platform failure. [cause] then describes the
   *   failure.
   * @property cause Underlying platform or provider exception, when one is available.
   */
  public data class Unavailable(
    val reason: LocationUnavailableReason?,
    val cause: Throwable? = null,
  ) : HeadingEvent
}

/**
 * Keeps [HeadingEvent] open: callers' `when` needs an `else` branch. No provider in this library
 * emits it.
 */
internal data object UnspecifiedHeadingEvent : HeadingEvent

/**
 * Delivers the latest event and waits [interval] after each [HeadingEvent.Update]. Unlike `sample`,
 * this keeps the event that precedes completion, such as a final [HeadingEvent.Unavailable].
 */
internal fun Flow<HeadingEvent>.throttleUpdates(interval: Duration): Flow<HeadingEvent> =
  if (interval == Duration.ZERO) this
  else
    conflate().transform { event ->
      emit(event)
      if (event is HeadingEvent.Update) delay(interval)
    }
