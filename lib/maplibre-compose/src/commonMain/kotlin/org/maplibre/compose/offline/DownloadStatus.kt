package org.maplibre.compose.offline

/** Indicates whether an [OfflinePack] is actively downloading or has completed its download. */
public enum class DownloadStatus {
  /** The pack is incomplete and is not downloading. */
  Paused,

  /** The pack is incomplete and is downloading. */
  Downloading,

  /** The pack has completed its download. */
  Complete,
}
