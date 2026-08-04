package org.maplibre.compose.offline

import co.touchlab.kermit.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.nativeffi.offline.OfflineRegionDownloadState
import org.maplibre.nativeffi.offline.OfflineRegionStatus
import org.maplibre.nativeffi.resource.ResourceErrorReason

/**
 * How a native offline status or error becomes the [DownloadProgress] common code switches on.
 *
 * These are the two translations a live download runs through on every event, and the states they
 * have to distinguish do not all hold still against a real database: a resumed download reports an
 * error and a status change in the same drain, and completion arrives while the region is still
 * marked active. Constructing the native values directly is what makes each case something to
 * assert rather than something to catch.
 */
class OfflineProgressMappingTest {

  private val logger = Logger.withTag("offline-progress-mapping-test")

  @Test
  fun `an inactive incomplete region is paused, and carries its counts across`() {
    val progress =
      status(OfflineRegionDownloadState.INACTIVE, complete = false).toDownloadProgress(logger)

    assertEquals(
      DownloadProgress.Healthy(
        completedResourceCount = 12,
        completedResourceBytes = 3_400,
        completedTileCount = 9,
        completedTileBytes = 2_500,
        status = DownloadStatus.Paused,
        isRequiredResourceCountPrecise = true,
        requiredResourceCount = 20,
      ),
      progress,
    )
  }

  @Test
  fun `an active incomplete region is downloading`() {
    val progress =
      status(OfflineRegionDownloadState.ACTIVE, complete = false).toDownloadProgress(logger)

    assertEquals(DownloadStatus.Downloading, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * MapLibre leaves a finished region marked active — it stops fetching without changing its
   * download state — so completion has to win over the state, or a pack that has everything it
   * needs goes on reporting that it is downloading.
   */
  @Test
  fun `a complete region is complete even while it is still marked active`() {
    val progress =
      status(OfflineRegionDownloadState.ACTIVE, complete = true).toDownloadProgress(logger)

    assertEquals(DownloadStatus.Complete, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * Download states are value classes over Int, so a newer native runtime can report one this build
   * has never heard of. Paused is the honest answer; claiming a download is running is not.
   */
  @Test
  fun `an unrecognized download state is reported as paused`() {
    val progress =
      status(OfflineRegionDownloadState(999), complete = false).toDownloadProgress(logger)

    assertEquals(DownloadStatus.Paused, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * The strings are the MapLibre Android SDK's `OfflineRegionError` reasons, which is what common
   * code compares against; spelling them differently here would make the same failure look like a
   * different one depending on the platform.
   */
  @Test
  fun `error reasons use the same names every platform reports`() {
    assertEquals("REASON_SUCCESS", ResourceErrorReason.NONE.toDownloadErrorReason())
    assertEquals("REASON_NOT_FOUND", ResourceErrorReason.NOT_FOUND.toDownloadErrorReason())
    assertEquals("REASON_SERVER", ResourceErrorReason.SERVER.toDownloadErrorReason())
    assertEquals("REASON_CONNECTION", ResourceErrorReason.CONNECTION.toDownloadErrorReason())
    assertEquals("REASON_RATE_LIMIT", ResourceErrorReason.RATE_LIMIT.toDownloadErrorReason())
    assertEquals("REASON_OTHER", ResourceErrorReason.OTHER.toDownloadErrorReason())
    // Same reasoning as the unknown download state: an unmapped reason must still be a reason.
    assertEquals("REASON_OTHER", ResourceErrorReason(999).toDownloadErrorReason())
  }

  private fun status(
    downloadState: OfflineRegionDownloadState,
    complete: Boolean,
  ): OfflineRegionStatus =
    OfflineRegionStatus(
      downloadState = downloadState,
      completedResourceCount = 12,
      completedResourceSize = 3_400,
      completedTileCount = 9,
      requiredTileCount = 15,
      completedTileSize = 2_500,
      requiredResourceCount = 20,
      requiredResourceCountIsPrecise = true,
      complete = complete,
    )
}
