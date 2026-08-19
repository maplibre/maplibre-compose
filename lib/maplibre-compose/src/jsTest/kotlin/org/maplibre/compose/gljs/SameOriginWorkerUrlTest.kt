package org.maplibre.compose.gljs

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

class SameOriginWorkerUrlTest {

  @Test
  fun a_same_origin_path_is_returned_as_it_is() {
    assertEquals("/maplibre-gl-worker.mjs", sameOriginWorkerUrl("/maplibre-gl-worker.mjs"))
  }

  @Test
  fun a_same_origin_absolute_url_is_returned_as_it_is() {
    val url = "${window.location.origin}/maplibre-gl-worker.mjs"
    assertEquals(url, sameOriginWorkerUrl(url))
  }

  @Test
  fun a_same_origin_protocol_relative_url_is_returned_as_it_is() {
    val url = "//${window.location.host}/maplibre-gl-worker.mjs"
    assertEquals(url, sameOriginWorkerUrl(url))
  }

  @Test
  fun an_unresolvable_url_is_returned_as_it_is() {
    assertEquals("https://:", sameOriginWorkerUrl("https://:"))
  }

  @Test
  fun a_cross_origin_https_url_is_imported_from_a_blob(): Promise<*> = workerUrlTest {
    val url = "https://cdn.example.com/maplibre-gl-worker.mjs"
    val result = sameOriginWorkerUrl(url)
    assertTrue(result.startsWith("blob:"), result)
    assertEquals("""import "$url"""", blobSource(result))
  }

  @Test
  fun a_protocol_relative_cross_origin_url_is_imported_with_a_scheme(): Promise<*> = workerUrlTest {
    val result = sameOriginWorkerUrl("//cdn.example.com/maplibre-gl-worker.mjs")
    assertTrue(result.startsWith("blob:"), result)
    assertEquals(
      """import "${window.location.protocol}//cdn.example.com/maplibre-gl-worker.mjs"""",
      blobSource(result),
    )
  }

  @Test
  fun a_mixed_case_https_url_is_imported_as_https(): Promise<*> = workerUrlTest {
    val result = sameOriginWorkerUrl("HTTPS://cdn.example.com/maplibre-gl-worker.mjs")
    assertTrue(result.startsWith("blob:"), result)
    assertEquals("""import "https://cdn.example.com/maplibre-gl-worker.mjs"""", blobSource(result))
  }

  private fun workerUrlTest(block: suspend () -> Unit): Promise<*> = MainScope().promise { block() }

  private suspend fun blobSource(blobUrl: String): String {
    val text =
      (js("fetch(blobUrl).then(function(response) { return response.text(); })") as Promise<String>)
        .await()
    js("URL.revokeObjectURL(blobUrl)")
    return text
  }
}
