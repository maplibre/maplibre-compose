# 10: Decide Material 3 test coverage

**What to build:** Either add layer-1 tests for `lib/maplibre-compose-material3`
(theme, control placement, slot wiring) or document in `AGENTS.md` that the
module is covered only through the demo app.

**Blocked by:** 01

**Type:** task

**Status:** ready-for-human

The module has no test source set. Do not add live-map screenshot tests as the
first coverage. Controls that only wrap Compose can use `runPlainComposeUiTest`.
