package org.maplibre.compose.offline

/** Represents whether an [OfflinePack] is actively downloading or has completed downloading. */
public enum class DownloadStatus {
  /** The pack is incomplete and is not currently downloading. */
  Paused,
  /** The pack is incomplete and is currently downloading. */
  Downloading,
  /** The pack has downloaded to completion. */
  Complete,
}
