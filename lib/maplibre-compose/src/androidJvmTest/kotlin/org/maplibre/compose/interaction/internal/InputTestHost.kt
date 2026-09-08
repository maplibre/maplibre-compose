@file:OptIn(ExperimentalAtomicApi::class, ExperimentalTestApi::class)

package org.maplibre.compose.interaction.internal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import org.maplibre.compose.map.GestureTestFixture
import org.maplibre.compose.map.RecordingGestureTarget
import org.maplibre.compose.mlnffi.runPlainComposeUiTest

internal const val RECOGNITION_MAP_TAG = "recognition-map"
internal const val BEFORE_MAP_TAG = "before-map"
internal const val AFTER_MAP_TAG = "after-map"

/** Skips tests when the Compose test host cannot inject pan and scale events. */
internal expect fun assumeTrackpadEventInjectionSupported()

const val TIMEOUT = 5_000L
const val FRAME_MILLIS = 16L
const val SECOND_TAP_GAP_MILLIS = 80L
val SCROLL_HOLD_MILLIS =
  InputConfiguration.Standard.bindings.scroll.idleDuration.inWholeMilliseconds

/**
 * Places the map between two focusables and records every key press or release that reaches the
 * parent, which is every one the map does not consume.
 */
internal fun GestureTestFixture.runFocusTest(
  options: InputConfiguration = InputConfiguration.Standard,
  rotaryNotchPixels: Float = 0f,
  optionsProvider: () -> InputConfiguration = { options },
  body: ComposeUiTest.(RecordingGestureTarget, List<Key>) -> Unit,
) = runPlainComposeUiTest {
  val target = this@runFocusTest.target
  val unconsumed = mutableListOf<Key>()
  setContent {
    Row(
      Modifier.fillMaxSize().onKeyEvent {
        unconsumed += it.key
        false
      }
    ) {
      Box(Modifier.size(40.dp).testTag(BEFORE_MAP_TAG).focusable())
      Box(Modifier.size(200.dp)) {
        GestureHost(target, optionsProvider(), rotaryNotchPixels)
      }
      Box(Modifier.size(40.dp).testTag(AFTER_MAP_TAG).focusable())
    }
  }
  waitForIdle()
  body(target, unconsumed)
}

internal fun GestureTestFixture.runRecognitionTest(
  options: InputConfiguration = InputConfiguration.Standard,
  parentOnClick: (() -> Unit)? = null,
  parentOnLongClick: (() -> Unit)? = null,
  parentModifier: Modifier = Modifier,
  optionsProvider: () -> InputConfiguration = { options },
  body: ComposeUiTest.(RecordingGestureTarget) -> Unit,
) = runPlainComposeUiTest {
  val target = this@runRecognitionTest.target
  setContent {
    val host: @Composable () -> Unit = { GestureHost(target, optionsProvider()) }
    when {
      parentOnLongClick != null ->
        Box(
          parentModifier
            .fillMaxSize()
            .combinedClickable(onClick = {}, onLongClick = parentOnLongClick)
        ) {
          host()
        }
      parentOnClick != null ->
        Box(parentModifier.fillMaxSize().clickable(onClick = parentOnClick)) { host() }
      else -> Box(parentModifier.fillMaxSize()) { host() }
    }
  }
  waitForIdle()
  body(target)
}

internal fun ComposeUiTest.awaitClicks(target: RecordingGestureTarget, count: Int) {
  waitUntil(timeoutMillis = TIMEOUT) { target.clicks == count }
}

/** Parent clickable nodes merge semantics. The map tag is only in the unmerged tree. */
internal fun ComposeUiTest.mapNode(): SemanticsNodeInteraction =
  onNodeWithTag(RECOGNITION_MAP_TAG, useUnmergedTree = true)

@Composable
internal fun GestureHost(
  target: RecordingGestureTarget,
  options: InputConfiguration,
  rotaryNotchPixels: Float = 0f,
) {
  SideEffect { target.updateConfiguration(options) }
  val density = LocalDensity.current
  val focusRequester = remember { FocusRequester() }
  val focus = remember { InputFocus {} }
  val environment = remember {
    InputEnvironment(
      contentDescription = "map",
      engaged = "engaged",
      notEngaged = "not engaged",
      indication = null,
    )
  }
  Box(
    Modifier.fillMaxSize()
      .testTag(RECOGNITION_MAP_TAG)
      .mapInput(
        target,
        target::capture,
        { it in target.clickFamilies },
        options,
        density,
        focusRequester,
        focus,
        environment,
        rotaryNotchPixels,
      )
  )
}

internal fun Modifier.consumePointerEvents(
  pass: PointerEventPass,
  type: PointerEventType,
): Modifier =
  pointerInput(pass, type) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent(pass)
        if (event.type == type) event.changes.forEach { it.consume() }
      }
    }
  }
