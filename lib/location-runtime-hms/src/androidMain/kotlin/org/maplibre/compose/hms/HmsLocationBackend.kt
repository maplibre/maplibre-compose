package org.maplibre.compose.hms

import android.content.Context
import com.huawei.hms.api.ConnectionResult
import com.huawei.hms.api.HuaweiApiAvailability
import org.maplibre.compose.location.AndroidLocationBackendFactory
import org.maplibre.compose.location.LocationBackend
import org.maplibre.compose.location.createDefaultLocationProvider

/**
 * Installs Huawei fused location as the Android default.
 *
 * [java.util.ServiceLoader] discovers this backend when an application packages this module, so
 * [createDefaultLocationProvider] returns [createFusedLocationProvider]. [isAvailable] reports
 * whether Huawei Mobile Services is available on the device, and the default falls back to the
 * framework provider on a device without it. Android's framework provider continues to supply
 * heading.
 */
public class HmsLocationBackendFactory : AndroidLocationBackendFactory {
  override val id: String = HmsLocationBackendId

  override fun isAvailable(context: Context): Boolean =
    HuaweiApiAvailability.getInstance().isHuaweiMobileServicesAvailable(context) ==
      ConnectionResult.SUCCESS

  override fun createLocationBackend(context: Context): LocationBackend =
    FusedLocationBackend(context)
}

internal const val HmsLocationBackendId: String = "hms-fused"
