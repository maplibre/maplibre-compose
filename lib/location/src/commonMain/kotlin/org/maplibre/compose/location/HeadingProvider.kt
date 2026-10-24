package org.maplibre.compose.location

import androidx.compose.runtime.Immutable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

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
   * A provider reports conditions and platform failures that prevent heading delivery as
   * [HeadingEvent.Unavailable] instead of failing the flow. After
   * [HeadingUnavailableReason.Unreliable], the next [HeadingEvent.Update] means a valid heading is
   * available again. A device without a heading sensor emits [HeadingUnavailableReason.Unsupported]
   * and completes.
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
   * The sensor cannot measure a valid heading now, because it needs calibration or because a nearby
   * magnetic field disturbs it.
   *
   * Platforms often cannot tell these two causes apart. The provider keeps measuring and delivers
   * the next valid heading. The user can help by moving the device in a figure-eight pattern or by
   * moving away from metal objects and electronics.
   */
  public data object Unreliable : HeadingUnavailableReason
}

/**
 * Keeps [HeadingUnavailableReason] open: callers' `when` needs an `else` branch. No provider in
 * this library reports it.
 */
internal data object UnspecifiedHeadingUnavailableReason : HeadingUnavailableReason

/**
 * Delivers at most one [HeadingEvent.Update] per [interval], keeping the latest one, and delivers
 * [HeadingEvent.Unavailable] immediately. Unlike `sample`, this delivers the first update without
 * waiting and keeps the last event before completion.
 */
internal fun Flow<HeadingEvent>.throttleUpdates(interval: Duration): Flow<HeadingEvent> =
  if (interval == Duration.ZERO) this
  else
    flow {
      coroutineScope {
        val events = this@throttleUpdates.produceIn(this)
        var cooldown: Job? = null
        var pending: HeadingEvent.Update? = null
        while (true) {
          // A null result means the cooldown ended.
          val received =
            select<ChannelResult<HeadingEvent>?> {
              events.onReceiveCatching { it }
              cooldown?.onJoin { null }
            }
          if (received == null) {
            cooldown = null
            pending?.let { update ->
              pending = null
              emit(update)
              cooldown = launch { delay(interval) }
            }
            continue
          }
          val event = received.getOrNull()
          when {
            event == null -> {
              cooldown?.join()
              pending?.let { emit(it) }
              break
            }
            event !is HeadingEvent.Update -> {
              pending = null
              emit(event)
            }
            cooldown == null -> {
              emit(event)
              cooldown = launch { delay(interval) }
            }
            else -> pending = event
          }
        }
      }
    }
