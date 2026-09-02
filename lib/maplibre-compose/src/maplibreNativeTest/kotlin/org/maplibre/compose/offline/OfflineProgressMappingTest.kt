package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.logging.MapLog
import org.maplibre.nativeffi.offline.OfflineRegionDownloadState
import org.maplibre.nativeffi.offline.OfflineRegionStatus
import org.maplibre.nativeffi.resource.ResourceErrorReason

/** How a native offline status or error becomes the [DownloadProgress] common code switches on. */
class OfflineProgressMappingTest {

  private val logger = MapLog()

  @Test
  fun an_inactive_incomplete_region_is_paused_and_carries_its_counts_across() {
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
  fun an_active_incomplete_region_is_downloading() {
    val progress =
      status(OfflineRegionDownloadState.ACTIVE, complete = false).toDownloadProgress(logger)

    assertEquals(DownloadStatus.Downloading, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * MapLibre leaves a finished region marked active — it stops fetching without changing its
   * download state — so completion has to win over the state.
   */
  @Test
  fun a_complete_region_is_complete_even_while_it_is_still_marked_active() {
    val progress =
      status(OfflineRegionDownloadState.ACTIVE, complete = true).toDownloadProgress(logger)

    assertEquals(DownloadStatus.Complete, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * Download states are value classes over Int, so a newer native runtime can report one this build
   * has never heard of. Paused is the honest answer; claiming a download is running is not.
   */
  @Test
  fun an_unrecognized_download_state_is_reported_as_paused() {
    val progress =
      status(OfflineRegionDownloadState(999), complete = false).toDownloadProgress(logger)

    assertEquals(DownloadStatus.Paused, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * The strings are the MapLibre Android SDK's `OfflineRegionError` reasons, which is what common
   * code compares against.
   */
  @Test
  fun error_reasons_use_the_same_names_every_platform_reports() {
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
