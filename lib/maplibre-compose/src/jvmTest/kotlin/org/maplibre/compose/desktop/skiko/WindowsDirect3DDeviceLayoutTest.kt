package org.maplibre.compose.desktop.skiko

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.maplibre.compose.mlnffi.MlnFfiHostException

/**
 * Pins the layout of Skiko's native `DirectXDevice`, which the Windows host reads by byte offset. A
 * C++ struct carries no runtime metadata, so the offsets can only be checked indirectly: by pinning
 * the Skiko version they were read from, and by exercising their cross-check against a struct built
 * here.
 *
 * A failed version assertion means Skiko was upgraded, and someone has to re-read
 * `skiko/src/awtMain/cpp/windows/directXRedrawer.cc` and update [SkikoDirect3DDeviceLayout].
 */
class WindowsDirect3DDeviceLayoutTest {

  @Test
  fun `the offsets were derived from the Skiko on the classpath`() {
    val skiko = SkikoDirect3DDeviceLayout.classpathSkikoVersion()

    assertEquals(
      SkikoDirect3DDeviceLayout.VERIFIED_SKIKO_VERSION,
      skiko,
      "The Windows map host reads Compose's ID3D12Device out of Skiko's DirectXDevice at a fixed " +
        "byte offset, derived by reading the C++ source of Skiko " +
        "${SkikoDirect3DDeviceLayout.VERIFIED_SKIKO_VERSION}. Skiko $skiko is a different struct " +
        "until someone re-reads it; see SkikoDirect3DDeviceLayout.",
    )
  }

  @Test
  fun `the device is read when both copies of it agree`() {
    Arena.ofConfined().use { arena ->
      // Any recognisable non-null value does; nothing dereferences it.
      val device = 0x0000_7FFA_1234_5678L
      val struct = directXDevice(arena, backendContextDevice = device, deviceField = device)

      assertEquals(device, SkikoDirect3DDeviceLayout.read(struct))
    }
  }

  @Test
  fun `a struct whose two copies disagree is refused`() {
    Arena.ofConfined().use { arena ->
      val struct =
        directXDevice(
          arena,
          backendContextDevice = 0x0000_7FFA_1234_5678L,
          deviceField = 0x0000_7FFA_8765_4321L,
        )

      val error = assertFailsWith<MlnFfiHostException> { SkikoDirect3DDeviceLayout.read(struct) }
      assertTrue(
        error.message.orEmpty().contains(SkikoDirect3DDeviceLayout.VERIFIED_SKIKO_VERSION),
        "The mismatch has one likely cause and the message should name it, but it said: " +
          "${error.message}",
      )
    }
  }

  @Test
  fun `a struct Skiko has not filled in yet is refused`() {
    Arena.ofConfined().use { arena ->
      // Skiko allocates DirectXDevice before the swap chain exists, so an all-zero struct is not a
      // layout change and must not be reported as one.
      val struct = directXDevice(arena, backendContextDevice = 0L, deviceField = 0L)

      val error = assertFailsWith<MlnFfiHostException> { SkikoDirect3DDeviceLayout.read(struct) }
      assertTrue(
        !error.message.orEmpty().contains(SkikoDirect3DDeviceLayout.VERIFIED_SKIKO_VERSION),
        "An unfilled device should not be blamed on a Skiko upgrade, but it said: ${error.message}",
      )
    }
  }

  /** A `DirectXDevice` with the two `ID3D12Device` pointers where Skiko puts them. */
  private fun directXDevice(
    arena: Arena,
    backendContextDevice: Long,
    deviceField: Long,
  ): MemorySegment =
    arena.allocate(SkikoDirect3DDeviceLayout.READ_SIZE).apply {
      set(
        ValueLayout.ADDRESS,
        SkikoDirect3DDeviceLayout.BACKEND_CONTEXT_DEVICE_OFFSET,
        MemorySegment.ofAddress(backendContextDevice),
      )
      set(
        ValueLayout.ADDRESS,
        SkikoDirect3DDeviceLayout.DEVICE_OFFSET,
        MemorySegment.ofAddress(deviceField),
      )
    }
}
