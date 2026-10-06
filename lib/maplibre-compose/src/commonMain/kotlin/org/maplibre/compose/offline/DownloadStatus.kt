package org.maplibre.compose.offline

import androidx.compose.runtime.Immutable

/**
 * Indicates whether an [OfflinePack] is actively downloading or has completed its download.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@Immutable
public sealed interface DownloadStatus {
  /** The pack is incomplete and is not downloading. */
  public data object Paused : DownloadStatus

  /** The pack is incomplete and is downloading. */
  public data object Downloading : DownloadStatus

  /** The pack has completed its download. */
  public data object Complete : DownloadStatus
}

/**
 * A download state that MapLibre Native reports and this version does not name. [nativeState] is
 * the number that MapLibre Native reports.
 */
internal data class UnrecognizedDownloadStatus(val nativeState: Int) : DownloadStatus
