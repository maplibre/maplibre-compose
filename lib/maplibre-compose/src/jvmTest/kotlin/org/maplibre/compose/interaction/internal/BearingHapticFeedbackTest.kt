package org.maplibre.compose.interaction.internal

import java.awt.EventQueue
import kotlin.test.Test
import org.junit.Assume.assumeTrue

class BearingHapticFeedbackTest {
  @Test
  fun macos_alignment_feedback_calls_the_platform_performer() {
    assumeTrue(System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true))
    EventQueue.invokeAndWait { performMacosBearingHaptic() }
  }
}
