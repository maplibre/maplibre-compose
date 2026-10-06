package org.maplibre.compose.offline

import kotlin.jvm.JvmInline

/**
 * Indicates whether an [OfflinePack] is actively downloading or has completed its download.
 *
 * [value] names the status, such as `Downloading`. MapLibre Native reports its download state as a
 * number, so a state that has no name here holds that number as decimal text, such as `2`.
 *
 * Values may be added in minor releases; use an `else` branch when matching.
 */
@JvmInline
public value class DownloadStatus internal constructor(public val value: String) {
  public companion object {
    /** The pack is incomplete and is not downloading. */
    public val Paused: DownloadStatus = DownloadStatus("Paused")

    /** The pack is incomplete and is downloading. */
    public val Downloading: DownloadStatus = DownloadStatus("Downloading")

    /** The pack has completed its download. */
    public val Complete: DownloadStatus = DownloadStatus("Complete")
  }
}
