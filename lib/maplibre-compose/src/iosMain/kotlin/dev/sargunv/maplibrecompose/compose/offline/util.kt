package dev.sargunv.maplibrecompose.compose.offline

import platform.Foundation.NSError

internal fun NSError.toOfflineRegionException(): OfflineRegionException {
  return OfflineRegionException(message = localizedDescription)
}
