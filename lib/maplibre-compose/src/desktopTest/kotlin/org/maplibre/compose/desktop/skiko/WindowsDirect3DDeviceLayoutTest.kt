package org.maplibre.compose.desktop.skiko

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pins the layout of Skiko's native `DirectXDevice`, which the Windows host reads by byte offset.
 *
 * [SkikoReflectionContractTest] can assert that a Java member still exists, and
 * [MacosMetalDeviceContractTest] can ask the Objective-C runtime the same question about a class
 * with no Java form. Neither trick reaches a plain C++ struct: `DirectXDevice` carries no metadata
 * at runtime, so nothing on this machine can be interrogated about where its `ID3D12Device` sits.
 * The only checks available are therefore indirect, and this test makes both of them.
 *
 * The first is that Skiko has not moved off the version whose source the offsets were read from.
 * The derivation is recorded on [SkikoDirect3DDeviceLayout]; a Skiko bump invalidates it and has to
 * fail here, because on any machine we build on it would otherwise fail nowhere at all — Windows is
 * the platform least likely to be exercised during development, and the symptom there is a blank
 * map.
 *
 * The second is that the cross-check protecting the offsets behaves, which can be tested properly
 * because it is arithmetic on a struct rather than anything that needs a GPU: this builds a
 * `DirectXDevice` of its own and hands it over.
 *
 * A failure of the version assertion is not a bug in MapLibre Compose. It means Skiko was upgraded
 * and someone has to re-read `skiko/src/awtMain/cpp/windows/directXRedrawer.cc` and the Skia
 * headers it includes, then update [SkikoDirect3DDeviceLayout] to match.
 */
class WindowsDirect3DDeviceLayoutTest {

  @Test
  fun `the offsets were derived from the Skiko on the classpath`() {
    val version = Class.forName("org.jetbrains.skiko.Version")
    val skiko = version.getMethod("getSkiko").invoke(version.getField("INSTANCE").get(null))

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
      // Any recognisable non-null value does: nothing dereferences it, the point is only that the
      // two offsets are where Skiko's two assignments land.
      val device = 0x0000_7FFA_1234_5678L
      val struct = directXDevice(arena, backendContextDevice = device, deviceField = device)

      assertEquals(device, SkikoDirect3DDeviceLayout.read(struct))
    }
  }

  @Test
  fun `a struct whose two copies disagree is refused`() {
    Arena.ofConfined().use { arena ->
      // What an inserted or reordered field looks like from here: one read still finds the device
      // and the other finds whatever moved into its place.
      val struct =
        directXDevice(
          arena,
          backendContextDevice = 0x0000_7FFA_1234_5678L,
          deviceField = 0x0000_7FFA_8765_4321L,
        )

      val error = assertFailsWith<DesktopHostException> { SkikoDirect3DDeviceLayout.read(struct) }
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
      // Skiko allocates DirectXDevice before the swap chain exists, so a caller can reach a real
      // struct that simply has no device in it yet. That is a different diagnosis from a layout
      // change and must not be reported as one.
      val struct = directXDevice(arena, backendContextDevice = 0L, deviceField = 0L)

      val error = assertFailsWith<DesktopHostException> { SkikoDirect3DDeviceLayout.read(struct) }
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
