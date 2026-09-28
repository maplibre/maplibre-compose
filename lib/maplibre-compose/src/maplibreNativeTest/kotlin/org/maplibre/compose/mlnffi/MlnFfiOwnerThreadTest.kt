package org.maplibre.compose.mlnffi

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class MlnFfiOwnerThreadTest {
  @Test
  fun the_owner_recognizes_itself_from_the_start_of_its_body() = runBlocking {
    val isOwner = CompletableDeferred<Boolean>()
    lateinit var thread: MlnFfiOwnerThread
    thread = MlnFfiOwnerThread("test-owner-identity") { isOwner.complete(thread.isCurrent()) }
    assertFalse(thread.isCurrent())
    thread.start()
    assertTrue(withTimeout(5_000) { isOwner.await() })
    assertFalse(thread.isCurrent())
  }
}
