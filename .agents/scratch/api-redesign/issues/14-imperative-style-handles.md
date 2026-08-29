# 14: Expose generation-bound imperative style handles

**What to build:** Expose imperative source and layer access through live
handles that belong to exactly one loaded style identity. Keep persistent
application content in StyleComposition.

**Blocked by:** 01: Separate reusable style definitions from loaded styles; 11:
Remove the superseded map APIs

**Status:** ready-for-agent

- [ ] Each live handle combines a resource ID with one opaque style identity.
- [ ] A handle can mutate only the loaded style that created it.
- [ ] Starting a base-style reload invalidates every outgoing handle.
- [ ] A stale handle fails clearly without mutating a replacement style or
      another map.
- [ ] Imperative mutations disappear after a base-style reload.
- [ ] Persistent sources, layers, and images remain the responsibility of
      StyleComposition.
- [ ] Immutable definitions remain reusable across maps and snapshotters.
