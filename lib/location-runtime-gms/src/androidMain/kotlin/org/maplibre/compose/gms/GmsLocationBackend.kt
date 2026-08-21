package org.maplibre.compose.gms

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.DeviceOrientationRequest
import kotlin.time.Duration
import org.maplibre.compose.location.AndroidLocationBackend
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.OrientationProvider
import org.maplibre.compose.location.rememberDefaultLocationProvider
import org.maplibre.compose.location.rememberDefaultOrientationProvider

/**
 * Installs the fused providers as the Android defaults.
 *
 * [java.util.ServiceLoader] discovers this backend when an application packages this module, so
 * [rememberDefaultLocationProvider] and [rememberDefaultOrientationProvider] return the fused
 * providers. [isAvailable] reports whether Google Play services is available on the device, and the
 * defaults fall back to the framework providers on a device without it.
 */
public class GmsLocationBackend : AndroidLocationBackend {
  override val id: String = "gms-fused"

  override fun isAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
      ConnectionResult.SUCCESS

  @Composable
  override fun rememberLocationProvider(): LocationProvider = rememberFusedLocationProvider()

  @Composable
  override fun rememberOrientationProvider(updateInterval: Duration): OrientationProvider {
    val request =
      remember(updateInterval) {
        DeviceOrientationRequest.Builder(updateInterval.inWholeMicroseconds).build()
      }
    return rememberFusedOrientationProvider(deviceOrientationRequest = request)
  }
}
