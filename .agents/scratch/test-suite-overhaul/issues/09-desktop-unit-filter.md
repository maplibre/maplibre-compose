# 09: Add a desktop unit Gradle filter

**What to build:** A `-Pmaplibre.tests=unit|all` switch (or a `jvmUnitTest`
task) that runs layers 0–2 on the JVM without loading a GPU render driver.
Ticket 05 uses it on ARM desktop jobs.

**Blocked by:** 04

**Type:** task

**Status:** ready-for-agent

KMP compiles `commonTest`, `liveMapTest`, `maplibreNativeTest`, and `jvmTest`
into one `jvmTest` task, so source sets cannot be run apart at execution time
without a filter.

Prefer an explicit class allowlist or JUnit tags over package excludes.
`MlnFfiConversionsTest` and `MlnFfiMapPixelTest` share a package prefix.

`mise run test:desktop` stays `all`. A new `mise run test:desktop-unit` runs the
filter. CI ARM jobs call the unit task plus the OS-specific classes.

## Test ledger

- `test:desktop-unit` creates no Vulkan/Metal/D3D context.
- `test:desktop` still runs every live class on a machine with a GPU.
