---
name: release
description:
  Use when asked to prepare or tag a release, or to write or rewrite a release's
  notes.
---

# Release

A release runs from the previous `vMAJOR.MINOR.PATCH` tag to a `main` commit
whose CI passed. Keep that version format; `mise run version` prints the
checkout's release and next-patch snapshot versions.

## Find what changed

PR titles and descriptions show where to look, but confirm each change in the
code at the two endpoints, since a description can be stale.

- The Kotlin API: diff each published module's `lib/*/api/` dumps, including
  JVM, Android, and Klib ABI dumps. Read the public Kotlin declarations, KDoc,
  and implementation where a dump doesn't explain a behavior change.
- Modules and packages: diff `settings.gradle.kts`, `lib/*/build.gradle.kts`,
  public package names, and publishing conventions in `buildSrc/`. Look for
  added or removed artifacts and changes to dependency coordinates.
- Platforms: compare target declarations, runtime modules, minimum platform
  versions, and the support documented in `docs/src/content/docs/` at both
  endpoints. Distinguish library support from demo launchers.
- The native engine: compare the `maplibre-native-ffi` dependency in
  `gradle/libs.versions.toml` and the pinned release's API and engine changes
  that Compose exposes.
- The browser engine: compare `third_party/maplibre-gl-js`, its versions in
  `gradle/libs.versions.toml`, and `patches/maplibre-gl-js/`, then inspect the
  bindings and implementation under `lib/maplibre-compose/src/jsMain/`.

Report the net change: something added and reworked within the range appears
only in its final form, or not at all. Cover only the artifacts that the release
publishes.

## Write

- Only what library users observe. No tests, CI, refactors, demo changes, or
  maintainer tooling.
- Organize by what changed, not by PR, and link no PRs.
- Open with one or two sentences on the release's theme, then use whatever
  sections this release needs.
- For a breaking change, say what callers must change. Merge changes that share
  a pattern into one line with an example; the compiler and API reference give
  the details.
- Use plain language, one line per change where possible. No title line or links
  footer.

Revise the draft with the maintainer until they approve it.

## Publish

Notes travel in the signed, annotated tag. Its message is
`MapLibre Compose <version>`, a blank line, then the approved notes. End the
file with a newline, or the signature fuses onto the last line:

```sh
git tag -s --cleanup=verbatim -F <file> v<version> <commit>
```

Create and push the tag only when the maintainer says to. After Maven
publication succeeds, release CI copies the tag's body, without its title or
signature, into a GitHub release. Lightweight and title-only tags get the
fallback `MapLibre Compose <version>.` line.

For a release that is already published, edit its GitHub release notes after the
maintainer approves, and leave the tag unchanged.
