package org.maplibre.compose.location.desktop.linux

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.StructHelper
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusException
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationMeasurement
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.XdgPortalWindow
import org.maplibre.compose.location.desktop.linux.portal.LocationPortal
import org.maplibre.compose.location.desktop.linux.portal.PortalRequest
import org.maplibre.compose.location.desktop.linux.portal.PortalSession
import org.maplibre.compose.location.desktop.linux.portal.PortalTimestamp
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.meters

internal class DbusLocationPortal(private val window: XdgPortalWindow? = null) :
  LinuxLocationPortal {
  override val available: Boolean = detectPortal()

  override suspend fun requestPermission(): Boolean =
    withContext(Dispatchers.IO) {
      check(available) { "Location permission requires an available XDG Location portal" }

      var connection: DBusConnection? = null
      var session: PortalSession? = null
      try {
        connection = openConnection()
        val portal = connection.locationPortal()
        val sessionPath = portal.createSession(sessionOptions(LocationRequest()))
        session = connection.portalSession(sessionPath)
        start(connection, portal, sessionPath) == 0L
      } catch (error: CancellationException) {
        throw error
      } catch (_: Throwable) {
        // The requester launches this in a SupervisorJob scope, so a throw would reach the
        // uncaught-exception handler. updates() reports the failure with its cause.
        false
      } finally {
        closeQuietly { session?.Close() }
        closeQuietly { connection?.close() }
      }
    }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    check(available) { "Location updates require an available XDG Location portal" }

    var connection: DBusConnection? = null
    var session: PortalSession? = null
    var locationSubscription: AutoCloseable? = null
    var closedSubscription: AutoCloseable? = null
    var serviceOwnerSubscription: AutoCloseable? = null
    try {
      connection = openConnection()
      val portal = connection.locationPortal()
      val sessionPath = portal.createSession(sessionOptions(request))
      session = connection.portalSession(sessionPath)
      locationSubscription =
        connection.addSigHandler(LocationPortal.LocationUpdated::class.java, portal) { signal ->
          if (signal.sessionHandle.path == sessionPath.path) {
            runCatching { signal.location.toLocationEvent() }
              .onSuccess(::trySend)
              .onFailure { error ->
                trySend(
                  LocationEvent.Unavailable(
                    LocationUnavailableReason.UnexpectedFailure,
                    error,
                  )
                )
              }
          }
        }
      closedSubscription =
        connection.addSigHandler(PortalSession.Closed::class.java, session) {
          trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
          close()
        }
      serviceOwnerSubscription =
        connection.addSigHandler(DBus.NameOwnerChanged::class.java) { signal ->
          if (signal.name == PortalBus && signal.newOwner.isEmpty()) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
            close()
          }
        }

      startFailure(start(connection, portal, sessionPath))?.let { reason ->
        trySend(LocationEvent.Unavailable(reason))
        close()
      }
      awaitClose()
    } catch (error: CancellationException) {
      throw error
    } catch (error: Throwable) {
      trySend(LocationEvent.Unavailable(error.asUnavailableReason(), error))
      close()
    } finally {
      closeQuietly { locationSubscription?.close() }
      closeQuietly { closedSubscription?.close() }
      closeQuietly { serviceOwnerSubscription?.close() }
      closeQuietly { session?.Close() }
      closeQuietly { connection?.close() }
    }
  }
    .flowOn(Dispatchers.IO)

  override fun close() = Unit

  private fun detectPortal(): Boolean =
    try {
      openConnection().use { connection ->
        val bus = connection.getRemoteObject(DbusBus, DbusPath, DBus::class.java)
        bus.StartServiceByName(PortalBus, UInt32(0))
        if (!bus.NameHasOwner(PortalBus)) return@use false

        val properties = connection.getRemoteObject(PortalBus, PortalPath, Properties::class.java)
        properties.Get<UInt32>(LocationInterface, "version")
        true
      }
    } catch (_: Throwable) {
      false
    }

  private suspend fun start(
    connection: DBusConnection,
    portal: LocationPortal,
    sessionPath: DBusPath,
  ): Long = window.withPortalParentWindow { parentWindow ->
    suspendCancellableCoroutine { continuation ->
      val subscription = AtomicReference<AutoCloseable?>()
      val request = AtomicReference<PortalRequest?>()
      val token = newToken()
      val responsePath = PortalResponsePath(connection.uniqueName, token)
      request.set(
        connection.getRemoteObject(PortalBus, responsePath.current, PortalRequest::class.java)
      )
      continuation.invokeOnCancellation {
        closeQuietly { request.get()?.Close() }
        closeQuietly { subscription.get()?.close() }
      }

      try {
        subscription.set(
          connection.addSigHandler(PortalRequest.Response::class.java) { signal ->
            if (!responsePath.accepts(signal.path)) return@addSigHandler
            closeQuietly { subscription.getAndSet(null)?.close() }
            if (continuation.isActive) continuation.resume(signal.response.toLong())
          }
        )
        val requestPath =
          portal.start(
            sessionPath,
            parentWindow,
            mapOf("handle_token" to Variant(token)),
          )
        responsePath.update(requestPath.path)
        request.set(
          connection.getRemoteObject(PortalBus, requestPath.path, PortalRequest::class.java)
        )
      } catch (error: Throwable) {
        closeQuietly { subscription.getAndSet(null)?.close() }
        if (continuation.isActive) continuation.resumeWithException(error)
      }
    }
  }

  private fun openConnection(): DBusConnection =
    DBusConnectionBuilder.forSessionBus().withShared(false).build()

  private fun DBusConnection.locationPortal(): LocationPortal =
    getRemoteObject(PortalBus, PortalPath, LocationPortal::class.java)

  private fun DBusConnection.portalSession(path: DBusPath): PortalSession =
    getRemoteObject(PortalBus, path.path, PortalSession::class.java)

  private companion object {
    const val PortalBus = "org.freedesktop.portal.Desktop"
    const val PortalPath = "/org/freedesktop/portal/desktop"
    const val LocationInterface = "org.freedesktop.portal.Location"
    const val DbusBus = "org.freedesktop.DBus"
    const val DbusPath = "/org/freedesktop/DBus"
  }
}

// A distance threshold suppresses even the first update for stationary GeoIP locations.
internal fun sessionOptions(request: LocationRequest): Map<String, Variant<*>> =
  mapOf(
    "session_handle_token" to Variant(newToken()),
    "time-threshold" to Variant(UInt32(request.minimumInterval.asPortalThreshold())),
    "accuracy" to Variant(UInt32(request.accuracy.portalValue)),
  )

/**
 * Maps a
 * [`Request.Response`](https://flatpak.github.io/xdg-desktop-portal/docs/doc-org.freedesktop.portal.Request.html#org-freedesktop-portal-request-response)
 * code from `Location.Start` to the reason the session cannot deliver locations, or null when it
 * started.
 */
internal fun startFailure(response: Long): LocationUnavailableReason? =
  when (response) {
    0L -> null
    1L -> LocationUnavailableReason.PermissionDenied
    else -> LocationUnavailableReason.TemporarilyUnavailable
  }

internal fun Throwable.asUnavailableReason(): LocationUnavailableReason =
  when (this) {
    is DBusException,
    is DBusExecutionException,
    is IOException -> LocationUnavailableReason.TemporarilyUnavailable
    else -> LocationUnavailableReason.UnexpectedFailure
  }

private val LocationAccuracy.portalValue: Long
  get() =
    when (this) {
      LocationAccuracy.BestForNavigation,
      LocationAccuracy.High -> 5L
      LocationAccuracy.Balanced -> 4L
      LocationAccuracy.Low -> 2L
      LocationAccuracy.Lowest -> 1L
    }

private fun Duration.asPortalThreshold(): Long =
  ceil(inWholeMilliseconds / 1_000.0).toLong().coerceIn(0, UInt32.MAX_VALUE)

internal fun Map<String, Variant<*>>.toLocationEvent(): LocationEvent.Update {
  val timestamp =
    get("Timestamp")?.let {
      StructHelper.createStructFromVariant(it, PortalTimestamp::class.java)
    }
  val capturedAt =
    timestamp?.let {
      Instant.fromEpochSeconds(
        it.seconds.toLong(),
        it.microseconds.toLong() * 1_000,
      )
    } ?: Clock.System.now()
  val location =
    LocationMeasurement(
      position =
        Position(
          longitude = number("Longitude") ?: error("Portal location has no Longitude"),
          latitude = number("Latitude") ?: error("Portal location has no Latitude"),
          // GeoClue reports unknown altitude as -G_MAXDOUBLE.
          altitude = number("Altitude")?.takeIf { it != -Double.MAX_VALUE },
        ),
      horizontalAccuracy = number("Accuracy")?.meters,
      distancePerSecond = number("Speed")?.takeIf { it >= 0.0 }?.meters,
      course = number("Heading")?.takeIf { it >= 0.0 }?.let { Bearing.North + it.degrees },
      measuredAt = capturedAt,
    )
  return LocationEvent.Update(
    measurement = location,
    measurementMark = TimeSource.Monotonic.markNow(),
  )
}

private fun Map<String, Variant<*>>.number(name: String): Double? =
  (get(name)?.value as? Number)?.toDouble()

private fun newToken(): String = "maplibre_${UUID.randomUUID().toString().replace("-", "")}"

internal fun portalRequestPath(uniqueName: String, token: String): String =
  "/org/freedesktop/portal/desktop/request/" +
    uniqueName.removePrefix(":").replace('.', '_') +
    "/" +
    token

internal class PortalResponsePath(uniqueName: String, token: String) {
  private val path = AtomicReference(portalRequestPath(uniqueName, token))

  val current: String
    get() = path.get()

  fun accepts(signalPath: String): Boolean = signalPath == current

  fun update(returnedPath: String) {
    path.set(returnedPath)
  }
}

private inline fun closeQuietly(action: () -> Unit) {
  runCatching(action)
}
