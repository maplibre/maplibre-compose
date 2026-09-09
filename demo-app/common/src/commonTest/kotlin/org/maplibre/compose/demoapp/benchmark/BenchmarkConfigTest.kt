package org.maplibre.compose.demoapp.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BenchmarkConfigTest {
  @Test
  fun absentLaunchKeepsNormalDemo() {
    assertNull(BenchmarkConfig.parse(null))
  }

  @Test
  fun allScenariosRoundTripWithAnUnsetCap() {
    BenchmarkScenario.entries.forEach { scenario ->
      val config = BenchmarkConfig(scenario, "texture", null, 5000)
      assertEquals(config, BenchmarkConfig.parse(config.encode()))
    }
  }

  @Test
  fun invalidConfigurationsCannotSilentlyMeasureSomethingElse() {
    listOf(
        "",
        "ease,surface,default,0",
        "animation,surface,0,0",
        "input,unknown,60,0",
        "setters,texture,60,10001",
        "setters,texture,60,0,extra",
      )
      .forEach {
        assertFailsWith<IllegalArgumentException> { BenchmarkConfig.parse(it) }
      }
  }

  @Test
  fun cancelAllowsANewRunAndResetsItsToken() {
    val ui = BenchmarkUiState()
    ui.requestRun()
    ui.requestRun()
    assertEquals(1, ui.runId)
    ui.abandonRun()
    assertEquals(0, ui.runId)
    ui.requestRun()
    assertEquals(2, ui.runId)
  }
}
