package org.maplibre.compose.offline

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.resource.MapResourceError
import org.maplibre.compose.resource.toCommon
import org.maplibre.compose.resource.toFfi
import org.maplibre.nativeffi.offline.OfflineRegionDownloadState
import org.maplibre.nativeffi.offline.OfflineRegionStatus
import org.maplibre.nativeffi.resource.ResourceErrorReason

/** How a native offline status or error becomes the [DownloadProgress] common code switches on. */
class OfflineProgressMappingTest {

  @Test
  fun an_inactive_incomplete_region_is_paused_and_carries_its_counts_across() {
    val progress =
      status(OfflineRegionDownloadState.INACTIVE, complete = false).toDownloadProgress()

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
    val progress = status(OfflineRegionDownloadState.ACTIVE, complete = false).toDownloadProgress()

    assertEquals(DownloadStatus.Downloading, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * MapLibre stops fetching completed regions without changing their active download state.
   * Completion must take precedence over that state.
   */
  @Test
  fun a_complete_region_is_complete_even_while_it_is_still_marked_active() {
    val progress = status(OfflineRegionDownloadState.ACTIVE, complete = true).toDownloadProgress()

    assertEquals(DownloadStatus.Complete, (progress as DownloadProgress.Healthy).status)
  }

  /**
   * Download states are value classes over Int, so a newer native runtime can report one this build
   * does not recognize. The status keeps its number.
   */
  @Test
  fun an_unrecognized_download_state_keeps_its_number() {
    val progress = status(OfflineRegionDownloadState(999), complete = false).toDownloadProgress()

    assertEquals(UnrecognizedDownloadStatus(999), (progress as DownloadProgress.Healthy).status)
  }

  @Test
  fun error_reasons_keep_their_names_and_unnamed_numbers() {
    assertEquals(MapResourceError.NotFound, ResourceErrorReason.NOT_FOUND.toCommon())
    assertEquals(MapResourceError.Server, ResourceErrorReason.SERVER.toCommon())
    assertEquals(MapResourceError.Connection, ResourceErrorReason.CONNECTION.toCommon())
    assertEquals(MapResourceError.RateLimit, ResourceErrorReason.RATE_LIMIT.toCommon())
    assertEquals(MapResourceError.Other, ResourceErrorReason.OTHER.toCommon())

    val unnamed = ResourceErrorReason(999).toCommon()
    assertEquals("999", unnamed.value)
    assertEquals(ResourceErrorReason(999), unnamed.toFfi())
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
