package org.maplibre.compose.location

import android.Manifest
import android.location.Criteria
import android.location.Location as AndroidLocation
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * A [GeoLocator] built on the [LocationManager] platform APIs.
 *
 * The [LocationManager.PASSIVE_PROVIDER] will be used for [DesiredAccuracy.Lowest], otherwise an
 * appropriate provider and configuration is chosen based on API level and [desiredAccuracy].
 *
 * @param locationManager the [LocationManager] system service
 * @param updateInterval the *minimum* time between location updates, the value is coerced to be at
 *   least 1 second
 * @param desiredAccuracy the [DesiredAccuracy] for location updates.
 */
public class AndroidGeoLocator
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
constructor(
  private val locationManager: LocationManager,
  updateInterval: Duration,
  private val desiredAccuracy: DesiredAccuracy,
  coroutineScope: CoroutineScope,
) : GeoLocator, LocationListener, RememberObserver {
  private val _location = MutableStateFlow<Location?>(null)
  override val location: StateFlow<Location?> = _location.asStateFlow()

  init {
    if (!handlerThread.isAlive) {
      handlerThread.start()
    }
    val updateInterval = updateInterval.coerceAtLeast(1.seconds)

    if (desiredAccuracy == DesiredAccuracy.Lowest) {
      initPassive(locationManager, updateInterval)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      initApi31(locationManager, updateInterval, desiredAccuracy)
    } else {
      initCompat(locationManager, updateInterval, desiredAccuracy)
    }

    coroutineScope.launch {
      _location.subscriptionCount
        .distinctUntilChangedBy { it > 0 }
        .collect { subscriptionCount ->
          if (subscriptionCount > 0) {
            if (desiredAccuracy == DesiredAccuracy.Lowest) {
              startPassive(locationManager, updateInterval)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
              startApi31(locationManager, updateInterval, desiredAccuracy)
            } else {
              startCompat(locationManager, updateInterval, desiredAccuracy)
            }
          } else {
            removeListener()
          }
        }
    }
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun initPassive(locationManager: LocationManager, updateInterval: Duration) {
    _location.value =
      locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)?.asMapLibreLocation()
  }

  @RequiresApi(Build.VERSION_CODES.S)
  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun initApi31(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
  ) {
    _location.value =
      locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.asMapLibreLocation()
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun initCompat(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
  ) {
    val criteria = getCriteria(desiredAccuracy)

    @Suppress("DEPRECATION")
    val provider = locationManager.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER
    _location.value = locationManager.getLastKnownLocation(provider)?.asMapLibreLocation()
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun startPassive(locationManager: LocationManager, updateInterval: Duration) {
    locationManager.requestLocationUpdates(
      LocationManager.PASSIVE_PROVIDER,
      updateInterval.inWholeMilliseconds,
      0f,
      this,
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
        .setMinUpdateIntervalMillis(1000)
        .build(),
      HandlerExecutor(Handler(handlerThread.looper)),
      this,
    )
  }

  @RequiresPermission(
    anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
  )
  private fun startCompat(
    locationManager: LocationManager,
    updateInterval: Duration,
    desiredAccuracy: DesiredAccuracy,
  ) {
    val criteria = getCriteria(desiredAccuracy)

    @Suppress("DEPRECATION")
    val provider = locationManager.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER

    locationManager.requestLocationUpdates(
      provider,
      updateInterval.inWholeMilliseconds,
      0f,
      this,
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

  override fun onLocationChanged(location: AndroidLocation) {
    _location.value = location.asMapLibreLocation()
  }

  override fun onRemembered() {}

  override fun onAbandoned() {
    removeListener()
  }

  override fun onForgotten() {
    removeListener()
  }

  private fun removeListener() {
    locationManager.removeUpdates(this)
  }

  private companion object {
    private val handlerThread by lazy { HandlerThread("AndroidGeoLocator") }
  }
}

@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public actual fun rememberDefaultGeoLocator(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
): GeoLocator {
  return rememberAndroidGeoLocator(updateInterval, desiredAccuracy)
}

/** Create and remember an [AndroidGeoLocator], the default [GeoLocator] for Android */
@Composable
@RequiresPermission(
  anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION]
)
public fun rememberAndroidGeoLocator(
  updateInterval: Duration,
  desiredAccuracy: DesiredAccuracy,
): AndroidGeoLocator {
  val context by rememberUpdatedState(LocalContext.current)
  val coroutineScope = rememberCoroutineScope()
  return remember(context) {
    AndroidGeoLocator(
      locationManager = context.getSystemService(LocationManager::class.java),
      updateInterval = updateInterval,
      desiredAccuracy = desiredAccuracy,
      coroutineScope = coroutineScope,
    )
  }
}
