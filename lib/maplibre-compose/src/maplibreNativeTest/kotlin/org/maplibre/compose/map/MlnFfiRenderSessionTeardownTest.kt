package org.maplibre.compose.map

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.logging.MapLogLevel
import org.maplibre.compose.logging.MapLogRecord
import org.maplibre.compose.logging.MapLogger
import org.maplibre.compose.logging.MapLogging
import org.maplibre.compose.mlnffi.BridgeMapFixture
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.testing.RecordingList
import org.maplibre.compose.testing.runMapTest

/**
 * Detaching and closing a map while its host is still driving frames. The lifecycle closes the
 * render session through the host's renderer thread, which may have a frame queued ahead of that
 * close; the frame must neither attach a second session nor attach one behind the close, or native
 * refuses to destroy the map and the runtime leaks.
 */
class MlnFfiRenderSessionTeardownTest {

  private val previousLogger = MapLogging.logger
  private val errors = RecordingList<MapLogRecord>()

  @BeforeTest
  fun captureErrors() {
    MapLogging.logger = MapLogger { record ->
      if (record.level >= MapLogLevel.Error) errors += record
      previousLogger?.log(record)
    }
  }

  @AfterTest
  fun restoreLogger() {
    MapLogging.logger = previousLogger
  }

  @Test
  fun detaching_and_closing_behind_queued_frames_destroys_the_map_and_runtime() = runMapTest {
    val fixture = BridgeMapFixture.create()
    fixture.loadStyleBeforeRendering(Style)
    fixture.withRendererThread {
      fixture.renderingPaused = false
      fixture.awaitRendered()
      fixture.session.detachPresentation()
      fixture.state.close()
      fixture.state.awaitClosed()
    }
    fixture.closeNow()
    assertEquals(
      emptyList(),
      errors.map { "${it.message}: ${it.throwable}" },
      "teardown behind queued frames should close the render session, map, and runtime",
    )
  }

  private companion object {
    /** Inline and layer-only, so the test needs no network. */
    val Style =
      BaseStyle.Json(
        """
        {"version":8,"sources":{},"layers":[
          {"id":"bg","type":"background","paint":{"background-color":"#eee"}}
        ]}
        """,
      )
  }
}
