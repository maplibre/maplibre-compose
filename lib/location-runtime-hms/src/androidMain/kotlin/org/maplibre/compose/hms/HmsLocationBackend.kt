package org.maplibre.compose.hms

import android.content.Context
import com.huawei.hms.api.ConnectionResult
import com.huawei.hms.api.HuaweiApiAvailability
import org.maplibre.compose.location.AndroidLocationBackend
import org.maplibre.compose.location.LocationProvider
import org.maplibre.compose.location.createDefaultLocationProvider

/**
 * Installs Huawei fused location as the Android default.
 *
 * [java.util.ServiceLoader] discovers this backend when an application packages this module, so
 * [createDefaultLocationProvider] returns [FusedLocationProvider]. [isAvailable] reports whether
 * Huawei Mobile Services is available on the device, and the default falls back to the framework
 * provider on a device without it. Android's framework provider continues to supply orientation.
 */
public class HmsLocationBackend : AndroidLocationBackend {
  override val id: String = HmsLocationBackendId

  override fun isAvailable(context: Context): Boolean =
    HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context) ==
      ConnectionResult.SUCCESS

  override fun createLocationProvider(context: Context): LocationProvider =
    FusedLocationProvider(context)
}

internal const val HmsLocationBackendId: String = "hms-fused"
