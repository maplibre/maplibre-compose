package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
   * A provider reports conditions that prevent heading delivery as [HeadingEvent.Unavailable]. A
   * device without a heading sensor emits [HeadingUnavailableReason.Unsupported] and completes.
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
   * @property reason Portable classification of the condition, or `null` when the provider has no
   *   classification for it, such as an unexpected platform failure. [cause] then describes the
   *   failure.
   * @property cause Underlying platform or provider exception, when one is available.
   */
  public data class Unavailable(
    val reason: HeadingUnavailableReason?,
    val cause: Throwable? = null,
  ) : HeadingEvent
}

/**
 * Keeps [HeadingEvent] open: callers' `when` needs an `else` branch. No provider in this library
 * emits it.
 */
internal data object UnspecifiedHeadingEvent : HeadingEvent

/**
 * Reasons that a provider cannot currently deliver heading measurements.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface HeadingUnavailableReason {
  /** The device has no sensor that can measure heading. */
  public data object Unsupported : HeadingUnavailableReason

  /**
   * The sensor cannot measure a valid heading now, because it needs calibration or a nearby
   * magnetic field disturbs it. The provider keeps measuring and delivers the next valid heading.
   *
   * Moving the device in a figure-eight pattern, or away from metal objects, can restore it.
   */
  public data object Unreliable : HeadingUnavailableReason
}

/**
 * Keeps [HeadingUnavailableReason] open: callers' `when` needs an `else` branch. No provider in
 * this library reports it.
 */
internal data object UnspecifiedHeadingUnavailableReason : HeadingUnavailableReason

/**
 * Applies [HeadingRequest.minimumInterval] to a stream of heading events, for a [HeadingProvider]
 * whose platform does not enforce it.
 *
 * The returned flow delivers the first [HeadingEvent.Update] immediately and then at most one
 * update per [minimumInterval], keeping the latest one. It delivers [HeadingEvent.Unavailable]
 * immediately and discards an update held back before it. When the upstream flow completes, the
 * returned flow delivers a held-back update after the interval ends, then completes.
 *
 * @param minimumInterval Minimum time between delivered updates. [Duration.ZERO] returns this flow
 *   unchanged.
 */
public fun Flow<HeadingEvent>.throttleUpdates(minimumInterval: Duration): Flow<HeadingEvent> =
  if (minimumInterval == Duration.ZERO) this
  else
    channelFlow {
      var cooldown: Job? = null
      collectLatest { event ->
        // A newer event cancels this wait, so only the latest held-back update is delivered.
        if (event is HeadingEvent.Update) cooldown?.join()
        send(event)
        if (event is HeadingEvent.Update) cooldown = launch { delay(minimumInterval) }
      }
      cooldown?.cancel()
    }
