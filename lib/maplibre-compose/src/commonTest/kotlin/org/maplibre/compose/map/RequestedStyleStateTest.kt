package org.maplibre.compose.map

import kotlin.test.Test
import kotlin.test.assertEquals
import org.maplibre.compose.style.BaseStyle

class RequestedStyleStateTest {

  private val styleA = BaseStyle.Uri("https://example.invalid/a.json")
  private val styleB = BaseStyle.Uri("https://example.invalid/b.json")

  @Test
  fun a_supplied_generation_is_the_generation_callbacks_read() {
    val state = RequestedStyleState()
    var clearedGeneration = 0L
    state.request(
      styleA,
      generation = 7L,
      unloadBinding = {},
      clearStyle = { clearedGeneration = state.requestedGeneration },
      postApply = {},
    )
    assertEquals(7L, state.requestedGeneration)
    assertEquals(7L, clearedGeneration)
    assertEquals(7L, state.requested?.generation)
  }

  @Test
  fun an_engine_only_request_mints_after_a_supplied_generation() {
    val state = RequestedStyleState()
    state.request(styleA, generation = 3L, unloadBinding = {}, clearStyle = {}, postApply = {})
    state.request(styleB, generation = 0L, unloadBinding = {}, clearStyle = {}, postApply = {})
    assertEquals(4L, state.requestedGeneration)
  }

  @Test
  fun the_same_generation_is_ignored() {
    var applies = 0
    val state = RequestedStyleState()
    state.request(
      styleA,
      generation = 2L,
      unloadBinding = {},
      clearStyle = {},
      postApply = { applies++ },
    )
    state.request(
      styleA,
      generation = 2L,
      unloadBinding = {},
      clearStyle = {},
      postApply = { applies++ },
    )
    assertEquals(2L, state.requestedGeneration)
    assertEquals(1, applies)
  }

  @Test
  fun a_newer_generation_of_the_same_style_is_a_new_request() {
    var applies = 0
    val state = RequestedStyleState()
    state.request(
      styleA,
      generation = 2L,
      unloadBinding = {},
      clearStyle = {},
      postApply = { applies++ },
    )
    state.request(
      styleA,
      generation = 9L,
      unloadBinding = {},
      clearStyle = {},
      postApply = { applies++ },
    )
    assertEquals(9L, state.requestedGeneration)
    assertEquals(2, applies)
  }

  @Test
  fun takeUnapplied_returns_a_newer_generation_of_the_applied_style() {
    val state = RequestedStyleState()
    state.request(styleA, generation = 2L, unloadBinding = {}, clearStyle = {}, postApply = {})
    state.takeUnapplied()?.let(state::markApplied)
    state.request(styleA, generation = 9L, unloadBinding = {}, clearStyle = {}, postApply = {})
    assertEquals(9L, state.takeUnapplied()?.generation)
  }
}
