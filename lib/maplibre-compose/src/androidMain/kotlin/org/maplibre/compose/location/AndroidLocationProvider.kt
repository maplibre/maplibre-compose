package org.maplibre.compose.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn

/**
 * A [LocationProvider] built on the [LocationManager] platform APIs.
 *
 * The [LocationManager.PASSIVE_PROVIDER] will be used for [DesiredAccuracy.Lowest], otherwise an
 * appropriate provider and configuration is chosen based on API level and [desiredAccuracy].
 *
 * @param context the [Context] get the [LocationManager] system service from
 * @param updateInterval the *minimum* time between location updates
 * @param desiredAccuracy the [DesiredAccuracy] for location updates.
 * @param coroutineScope the [CoroutineScope] used to share the [location] flow
 * @param sharingStarted parameter for [stateIn] call of [location]
 * @throws PermissionException if the necessary platform permissions have not been granted
 */
public class AndroidLocationProvider
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val context: Context,
  updateInterval: Duration,
  private val minDistanceMeters: Float,
  private val desiredAccuracy: DesiredAccuracy,
  coroutineScope: CoroutineScope,
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
) : LocationProvider {
  override val location: StateFlow<Location?>

  init {
    if (!handlerThread.isAlive) {
      handlerThread.start()
    }

    if (
      context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) !=
          PackageManager.PERMISSION_GRANTED
    ) {
      throw PermissionException()
    }

    val locationManager = context.getSystemService(LocationManager::class.java)

    location =
      callbackFlow {
          val lastLocation =
            if (desiredAccuracy == DesiredAccuracy.Lowest) {
              lastLocationPassive(locationManager, updateInterval)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              lastLocationApi31(locationManager, updateInterval, desiredAccuracy)
            } else {
              lastLocationCompat(locationManager, updateInterval, desiredAccuracy)
            }
          send(lastLocation)

          val listener = LocationListener { trySendBlocking(it.asMapLibreLocation()).getOrThrow() }

          if (desiredAccuracy == DesiredAccuracy.Lowest) {
            startPassive(locationManager, updateInterval, listener)
          } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            startApi31(locationManager, updateInterval, desiredAccuracy, listener)
          } else {
            startCompat(locationManager, updateInterval, desiredAccuracy, listener)
          }

          awaitClose { locationManager.removeUpdates(listener) }
        }
        .stateIn(coroutineScope, sharingStarted, null)
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun lastLocationPassive(
    locationManager: LocationManager,
    updateInterval: Duration,
  ): Location? {
    return locationManager
      .getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
      ?.asMapLibreLocation()
  }

  @RequiresApi(Build.VERSION_CODES.S)
  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun lastLocationApi31(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
  ): Location? {
    return locationManager
      .getLastKnownLocation(LocationManager.FUSED_PROVIDER)
      ?.asMapLibreLocation()
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun lastLocationCompat(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
  ): Location? {
    val criteria = getCriteria(desiredAccuracy)

    @Suppress("DEPRECATION")
    val provider = locationManager.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER
    return locationManager.getLastKnownLocation(provider)?.asMapLibreLocation()
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun startPassive(
    locationManager: LocationManager,
    updateInterval: Duration,
    listener: LocationListener,
  ) {
    locationManager.requestLocationUpdates(
      LocationManager.PASSIVE_PROVIDER,
      updateInterval.inWholeMilliseconds,
      minDistanceMeters,
      listener,
      handlerThread.looper,
    )
  }

  @RequiresApi(Build.VERSION_CODES.S)
  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun startApi31(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
    listener: LocationListener,
  ) {
    locationManager.requestLocationUpdates(
      LocationManager.FUSED_PROVIDER,
      LocationRequest.Builder(updateInterval.inWholeMilliseconds)
        .setQuality(
          when (desiredAccuracy) {
            DesiredAccuracy.Highest -> LocationRequest.QUALITY_HIGH_ACCURACY
            DesiredAccuracy.High -> LocationRequest.QUALITY_HIGH_ACCURACY
            DesiredAccuracy.Balanced -> LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
            DesiredAccuracy.Low -> LocationRequest.QUALITY_LOW_POWER
            DesiredAccuracy.Lowest -> error("unreachable")
          }
        )
        .setMinUpdateDistanceMeters(minDistanceMeters)
        .build(),
      HandlerExecutor(Handler(handlerThread.looper)),
      listener,
    )
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun startCompat(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
    listener: LocationListener,
  ) {
    val criteria = getCriteria(desiredAccuracy)

    @Suppress("DEPRECATION")
    val provider = locationManager.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER

    locationManager.requestLocationUpdates(
      provider,
      updateInterval.inWholeMilliseconds,
      minDistanceMeters,
      listener,
      handlerThread.looper,
    )
  }

  @Suppress("DEPRECATION")
  private fun getCriteria(desiredAccuracy: DesiredAccuracy): Criteria =
    Criteria().apply {
      accuracy =
        when (desiredAccuracy) {
          DesiredAccuracy.Highest -> Criteria.ACCURACY_FINE
          DesiredAccuracy.High -> Criteria.ACCURACY_FINE
          DesiredAccuracy.Balanced -> Criteria.ACCURACY_FINE
          DesiredAccuracy.Low -> Criteria.ACCURACY_COARSE
          DesiredAccuracy.Lowest -> error("unreachable")
        }
      isCostAllowed = true
      powerRequirement =
        when (desiredAccuracy) {
          DesiredAccuracy.Highest -> Criteria.POWER_HIGH
          DesiredAccuracy.High -> Criteria.POWER_HIGH
          DesiredAccuracy.Balanced -> Criteria.POWER_MEDIUM
          DesiredAccuracy.Low -> Criteria.POWER_LOW
          DesiredAccuracy.Lowest -> error("unreachable")
        }
    }

  private companion object {
    private val handlerThread by lazy { HandlerThread("AndroidLocationProvider") }
  }
}

@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public actual fun rememberDefaultLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistanceMeters: Double,
): LocationProvider {
  return rememberAndroidLocationProvider(
    updateInterval = updateInterval,
    desiredAccuracy = desiredAccuracy,
    minDistanceMeters = minDistanceMeters.toFloat(),
  )
}

/** Create and remember an [AndroidLocationProvider], the default [LocationProvider] for Android */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberAndroidLocationProvider(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
  minDistanceMeters: Float,
  context: Context = LocalContext.current,
  coroutineScope: CoroutineScope = rememberCoroutineScope(),
  sharingStarted: SharingStarted = SharingStarted.WhileSubscribed(stopTimeoutMillis = 1000),
): AndroidLocationProvider {
  return remember(
    context,
    updateInterval,
    desiredAccuracy,
    minDistanceMeters,
    coroutineScope,
    sharingStarted,
  ) {
    AndroidLocationProvider(
      context = context,
      updateInterval = updateInterval,
      desiredAccuracy = desiredAccuracy,
      minDistanceMeters = minDistanceMeters,
      coroutineScope = coroutineScope,
      sharingStarted = sharingStarted,
    )
  }
}
