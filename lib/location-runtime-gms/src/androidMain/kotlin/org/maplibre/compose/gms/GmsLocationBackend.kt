package org.maplibre.compose.gms

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.DeviceOrientationRequest
import com.google.android.gms.location.LocationServices
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import org.maplibre.compose.location.AndroidLocationBackend
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.compose.location.createDefaultLocationProvider
import org.maplibre.compose.location.createDefaultOrientationProvider

/**
 * Installs the fused providers as the Android defaults.
 *
 * [java.util.ServiceLoader] discovers this backend when an application packages this module, so
 * [createDefaultLocationProvider] and [createDefaultOrientationProvider] return the fused
 * providers. [isAvailable] reports whether Google Play services is available on the device, and the
 * defaults fall back to the framework providers on a device without it.
 */
public class GmsLocationBackend : AndroidLocationBackend {
  override val id: String = "gms-fused"

  override fun isAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
      ConnectionResult.SUCCESS

  override fun createLocationProvider(context: Context): LocationProvider =
    FusedLocationProvider(context)

  override fun createOrientationProvider(
    context: Context,
    updateInterval: Duration,
    coroutineScope: CoroutineScope,
  ): OrientationProvider =
    FusedOrientationProvider(
      orientationClient = LocationServices.getFusedOrientationProviderClient(context),
      deviceOrientationRequest =
        DeviceOrientationRequest.Builder(updateInterval.inWholeMicroseconds).build(),
      coroutineScope = coroutineScope,
    )
}
