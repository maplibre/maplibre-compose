# 15: Report runtime capabilities

**What to build:** Expose platform capability information through MapRuntime and
make unsupported common operations fail explicitly.

**Blocked by:** 03: Render through MapRuntime and MapState; 11: Remove the
superseded map APIs

**Status:** ready-for-agent

- [ ] MapRuntime exposes stable capability values for platform-dependent
      operations.
- [ ] Native runtimes report the cache and offline operations that they support.
- [ ] Web reports unsupported native cache and offline operations.
- [ ] Calling an unsupported common operation throws
      UnsupportedOperationException.
- [ ] Ordinary Web map and snapshotter creation remains available.
- [ ] Runtime options remain independent from presentation-host configuration.
- [ ] Common and platform tests verify reported capabilities and failures.
