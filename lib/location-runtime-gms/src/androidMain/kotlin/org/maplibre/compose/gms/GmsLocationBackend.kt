package org.maplibre.compose.gms

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationServices
import org.maplibre.compose.location.AndroidLocationBackend
import org.maplibre.compose.location.HeadingProvider
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.createDefaultHeadingProvider
import org.maplibre.compose.location.createDefaultLocationProvider

/**
 * Installs the fused providers as the Android defaults.
 *
 * [java.util.ServiceLoader] discovers this backend when an application packages this module, so
 * [createDefaultLocationProvider] and [createDefaultHeadingProvider] return the fused providers.
 * [isAvailable] reports whether Google Play services is available on the device, and the defaults
 * fall back to the framework providers on a device without it.
 */
public class GmsLocationBackend : AndroidLocationBackend {
  override val id: String = GmsLocationBackendId

  override fun isAvailable(context: Context): Boolean =
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
      ConnectionResult.SUCCESS

  override fun createLocationProvider(context: Context): LocationProvider =
    FusedLocationProvider(context)

  override fun createHeadingProvider(context: Context): HeadingProvider =
    FusedHeadingProvider(LocationServices.getFusedOrientationProviderClient(context))
}

internal const val GmsLocationBackendId: String = "gms-fused"
