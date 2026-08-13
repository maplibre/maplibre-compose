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
import org.maplibre.compose.desktop.ComposeMapHost
import org.maplibre.compose.location.BearingWithAccuracy
import org.maplibre.compose.location.Location
import org.maplibre.compose.location.LocationAccuracy
import org.maplibre.compose.location.LocationEvent
import org.maplibre.compose.location.LocationRequest
import org.maplibre.compose.location.LocationUnavailableReason
import org.maplibre.compose.location.PositionWithAccuracy
import org.maplibre.compose.location.SpeedWithAccuracy
import org.maplibre.compose.location.desktop.linux.portal.LocationPortal
import org.maplibre.compose.location.desktop.linux.portal.PortalRequest
import org.maplibre.compose.location.desktop.linux.portal.PortalSession
import org.maplibre.compose.location.desktop.linux.portal.PortalTimestamp
import org.maplibre.spatialk.geojson.Position
import org.maplibre.spatialk.units.Bearing
import org.maplibre.spatialk.units.Length
import org.maplibre.spatialk.units.extensions.degrees
import org.maplibre.spatialk.units.extensions.inMeters
import org.maplibre.spatialk.units.extensions.meters

internal class DbusLocationPortal(private val host: ComposeMapHost? = null) : LinuxLocationPortal {
  override val available: Boolean = detectPortal()

  override suspend fun requestPermission(): PortalPermissionResult =
    withContext(Dispatchers.IO) {
      if (!available)
        return@withContext PortalPermissionResult.Unavailable(LocationUnavailableReason.Unsupported)

      var connection: DBusConnection? = null
      var session: PortalSession? = null
      try {
        connection = openConnection()
        val portal = connection.locationPortal()
        val sessionPath = portal.createSession(sessionOptions(LocationRequest()))
        session = connection.portalSession(sessionPath)
        when (start(connection, portal, sessionPath)) {
          0L -> PortalPermissionResult.Granted
          1L -> PortalPermissionResult.Denied
          else ->
            PortalPermissionResult.Unavailable(LocationUnavailableReason.TemporarilyUnavailable)
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Throwable) {
        PortalPermissionResult.Unavailable(
          error.asUnavailableReason(),
          error,
        )
      } finally {
        closeQuietly { session?.Close() }
        closeQuietly { connection?.close() }
      }
    }

  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    if (!available) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.Unsupported))
      close()
      return@callbackFlow
    }

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
          if (signal.name == PORTAL_BUS && signal.newOwner.isEmpty()) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
            close()
          }
        }

      val response = start(connection, portal, sessionPath)
      when (response) {
        0L -> Unit
        1L -> {
          trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
          close()
        }
        else -> {
          trySend(LocationEvent.Unavailable(LocationUnavailableReason.TemporarilyUnavailable))
          close()
        }
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

  private fun Throwable.asUnavailableReason(): LocationUnavailableReason =
    when (this) {
      is DBusException,
      is DBusExecutionException,
      is IOException -> LocationUnavailableReason.TemporarilyUnavailable
      else -> LocationUnavailableReason.UnexpectedFailure
    }

  private fun detectPortal(): Boolean =
    try {
      openConnection().use { connection ->
        val bus = connection.getRemoteObject(DBUS_BUS, DBUS_PATH, DBus::class.java)
        bus.StartServiceByName(PORTAL_BUS, UInt32(0))
        if (!bus.NameHasOwner(PORTAL_BUS)) return@use false

        val properties = connection.getRemoteObject(PORTAL_BUS, PORTAL_PATH, Properties::class.java)
        properties.Get<UInt32>(LOCATION_INTERFACE, "version")
        true
      }
    } catch (_: Throwable) {
      false
    }

  private suspend fun start(
    connection: DBusConnection,
    portal: LocationPortal,
    sessionPath: DBusPath,
  ): Long = host.withPortalParentWindow { parentWindow ->
    suspendCancellableCoroutine { continuation ->
      val subscription = AtomicReference<AutoCloseable?>()
      val request = AtomicReference<PortalRequest?>()
      val token = newToken()
      val responsePath = PortalResponsePath(connection.uniqueName, token)
      request.set(
        connection.getRemoteObject(PORTAL_BUS, responsePath.current, PortalRequest::class.java)
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
          connection.getRemoteObject(PORTAL_BUS, requestPath.path, PortalRequest::class.java)
        )
      } catch (error: Throwable) {
        closeQuietly { subscription.getAndSet(null)?.close() }
        if (continuation.isActive) continuation.resumeWithException(error)
      }
    }
  }

  private fun sessionOptions(request: LocationRequest): Map<String, Variant<*>> =
    mapOf(
      "session_handle_token" to Variant(newToken()),
      "distance-threshold" to Variant(UInt32(request.minimumDistance.asPortalThreshold())),
      "time-threshold" to Variant(UInt32(request.minimumInterval.asPortalThreshold())),
      "accuracy" to Variant(UInt32(request.accuracy.portalValue)),
    )

  private fun openConnection(): DBusConnection =
    DBusConnectionBuilder.forSessionBus().withShared(false).build()

  private fun DBusConnection.locationPortal(): LocationPortal =
    getRemoteObject(PORTAL_BUS, PORTAL_PATH, LocationPortal::class.java)

  private fun DBusConnection.portalSession(path: DBusPath): PortalSession =
    getRemoteObject(PORTAL_BUS, path.path, PortalSession::class.java)

  private companion object {
    const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
    const val PORTAL_PATH = "/org/freedesktop/portal/desktop"
    const val LOCATION_INTERFACE = "org.freedesktop.portal.Location"
    const val DBUS_BUS = "org.freedesktop.DBus"
    const val DBUS_PATH = "/org/freedesktop/DBus"
  }
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

private fun Length.asPortalThreshold(): Long = ceil(inMeters).toLong().coerceIn(0, UInt32.MAX_VALUE)

private fun Duration.asPortalThreshold(): Long =
  ceil(inWholeMilliseconds / 1_000.0).toLong().coerceIn(0, UInt32.MAX_VALUE)

internal fun Map<String, Variant<*>>.toLocationEvent(): LocationEvent.Fix {
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
    Location(
      position =
        PositionWithAccuracy(
          value =
            Position(
              longitude = number("Longitude") ?: error("Portal location has no Longitude"),
              latitude = number("Latitude") ?: error("Portal location has no Latitude"),
              altitude = number("Altitude"),
            ),
          accuracy = number("Accuracy")?.meters,
        ),
      speed =
        number("Speed")
          ?.takeIf { it >= 0.0 }
          ?.let {
            SpeedWithAccuracy(it.meters, accuracy = null)
          },
      course =
        number("Heading")
          ?.takeIf { it >= 0.0 }
          ?.let {
            BearingWithAccuracy(Bearing.North + it.degrees, accuracy = null)
          },
      timestamp =
        TimeSource.Monotonic.markNow() -
          (Clock.System.now() - capturedAt).coerceAtLeast(Duration.ZERO),
    )
  return LocationEvent.Fix(location)
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
