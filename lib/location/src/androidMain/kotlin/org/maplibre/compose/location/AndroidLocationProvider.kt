package org.maplibre.compose.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location as AndroidLocation
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest as AndroidLocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import org.maplibre.spatialk.units.extensions.inMeters

/**
 * Foreground location from Android's location service.
 *
 * Uses fused location on Android 12 and later, and an available provider matching the requested
 * accuracy on earlier versions. [LocationAccuracy.Lowest] receives passive updates.
 *
 * Disabled location services report [LocationUnavailableReason.ServicesDisabled]. Missing
 * permission reports [LocationUnavailableReason.PermissionDenied]. Invalid provider registration
 * reports [LocationUnavailableReason.UnexpectedFailure].
 *
 * See [AndroidLocationPermissionRequester] for permission request requirements.
 *
 * @param context Context used to access location services.
 * @param requester Handles [permission] and [requestPermission].
 */
public class AndroidLocationProvider
internal constructor(context: Context, private val requester: AndroidLocationPermissionRequester) :
  LocationProvider {
  private val context: Context = context.applicationContext

  override val backendId: String = "android-framework"

  /** Creates a provider with its own [AndroidLocationPermissionRequester]. */
  public constructor(context: Context) : this(context, AndroidLocationPermissionRequester(context))

  override val permission: StateFlow<LocationPermission>
    get() = requester.status

  override fun requestPermission(): Unit = requester.requestForegroundPermission()

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  override fun updates(request: LocationRequest): Flow<LocationEvent> = callbackFlow {
    if (!context.hasLocationPermission()) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied))
      close()
      return@callbackFlow
    }

    val manager = context.getSystemService(LocationManager::class.java)
    val listener =
      object : LocationListener {
        override fun onLocationChanged(location: AndroidLocation) {
          trySend(location.asMapLibreLocationUpdate())
        }

        override fun onProviderDisabled(provider: String) {
          trySend(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
        }

        override fun onProviderEnabled(provider: String) = Unit
      }
    var registered = false

    fun refreshRegistration() {
      if (registered) {
        manager.removeUpdates(listener)
        registered = false
      }

      val provider = selectProvider(manager, request.accuracy)
      if (provider == null) {
        trySend(LocationEvent.Unavailable(LocationUnavailableReason.ServicesDisabled))
        return
      }

      manager.getLastKnownLocation(provider)?.let { location ->
        trySend(location.asMapLibreLocationUpdate())
      }
      if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && provider == LocationManager.FUSED_PROVIDER
      ) {
        startApi31(manager, request, listener)
      } else {
        manager.requestLocationUpdates(
          provider,
          request.minimumInterval.inWholeMilliseconds,
          request.minimumDistance.inMeters.toFloat(),
          listener,
          handlerThread.looper,
        )
      }
      registered = true
    }

    val settingsReceiver =
      object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          try {
            refreshRegistration()
          } catch (error: IllegalArgumentException) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
            close()
          } catch (error: SecurityException) {
            trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
            close()
          }
        }
      }

    try {
      context.registerLocationSettingsReceiver(settingsReceiver)
      refreshRegistration()
    } catch (error: IllegalArgumentException) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.UnexpectedFailure, error))
      close()
    } catch (error: SecurityException) {
      trySend(LocationEvent.Unavailable(LocationUnavailableReason.PermissionDenied, error))
      close()
    }

    awaitClose {
      runCatching { context.unregisterReceiver(settingsReceiver) }
      manager.removeUpdates(listener)
    }
  }

  @Suppress("DEPRECATION")
  private fun selectProvider(
    manager: LocationManager,
    accuracy: LocationAccuracy,
  ): String? {
    if (!manager.isLocationEnabledCompat()) return null
    if (accuracy == LocationAccuracy.Lowest) return LocationManager.PASSIVE_PROVIDER
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return LocationManager.FUSED_PROVIDER

    val criteria =
      Criteria().apply {
        this.accuracy =
          when (accuracy) {
            LocationAccuracy.BestForNavigation,
            LocationAccuracy.High,
            LocationAccuracy.Balanced -> Criteria.ACCURACY_FINE
            LocationAccuracy.Low -> Criteria.ACCURACY_COARSE
            LocationAccuracy.Lowest -> error("Lowest uses the passive provider")
          }
        isCostAllowed = true
        powerRequirement =
          when (accuracy) {
            LocationAccuracy.BestForNavigation,
            LocationAccuracy.High -> Criteria.POWER_HIGH
            LocationAccuracy.Balanced -> Criteria.POWER_MEDIUM
            LocationAccuracy.Low -> Criteria.POWER_LOW
            LocationAccuracy.Lowest -> error("Lowest uses the passive provider")
          }
      }

    return manager.getBestProvider(criteria, true)
  }

  @RequiresApi(Build.VERSION_CODES.S)
  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun startApi31(
    manager: LocationManager,
    request: LocationRequest,
    listener: LocationListener,
  ) {
    manager.requestLocationUpdates(
      LocationManager.FUSED_PROVIDER,
      AndroidLocationRequest.Builder(request.minimumInterval.inWholeMilliseconds)
        .setQuality(
          when (request.accuracy) {
            LocationAccuracy.BestForNavigation,
            LocationAccuracy.High -> AndroidLocationRequest.QUALITY_HIGH_ACCURACY
            LocationAccuracy.Balanced -> AndroidLocationRequest.QUALITY_BALANCED_POWER_ACCURACY
            LocationAccuracy.Low -> AndroidLocationRequest.QUALITY_LOW_POWER
            LocationAccuracy.Lowest -> AndroidLocationRequest.QUALITY_LOW_POWER
          }
        )
        .setMinUpdateDistanceMeters(request.minimumDistance.inMeters.toFloat())
        .build(),
      HandlerExecutor(Handler(handlerThread.looper)),
      listener,
    )
  }

  private companion object {
    private val handlerThread by lazy {
      HandlerThread("AndroidLocationProvider").apply { start() }
    }
  }
}

/**
 * Creates the default Android location provider: the discovered backend's provider when one is
 * available, a provider that reports the failure when discovery is misconfigured, and the framework
 * [AndroidLocationProvider] otherwise.
 */
public fun createDefaultLocationProvider(context: Context): LocationProvider =
  when (val resolution = AndroidLocationBackendResolver.discover(context)) {
    is AndroidBackendResolution.Discovered ->
      IdentifiedLocationProvider(
        backendId = resolution.backend.id,
        delegate = resolution.backend.createLocationProvider(context),
      )
    is AndroidBackendResolution.Misconfigured -> MisconfiguredLocationProvider(resolution.cause)
    AndroidBackendResolution.None ->
      AndroidLocationProvider(context, AndroidLocationPermissionRequester(context))
  }

private class IdentifiedLocationProvider(
  override val backendId: String,
  private val delegate: LocationProvider,
) : LocationProvider by delegate

private fun Context.hasLocationPermission(): Boolean =
  checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
    PackageManager.PERMISSION_GRANTED ||
    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED

private fun Context.registerLocationSettingsReceiver(receiver: BroadcastReceiver) {
  val filter =
    IntentFilter().apply {
      addAction(LocationManager.MODE_CHANGED_ACTION)
      addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
    }
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
  } else {
    @Suppress("DEPRECATION") registerReceiver(receiver, filter)
  }
}

@Suppress("DEPRECATION")
private fun LocationManager.isLocationEnabledCompat(): Boolean =
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    isLocationEnabled
  } else {
    isProviderEnabled(LocationManager.GPS_PROVIDER) ||
      isProviderEnabled(LocationManager.NETWORK_PROVIDER)
  }
