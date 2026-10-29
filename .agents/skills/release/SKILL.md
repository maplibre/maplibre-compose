---
name: release
description:
  Use when asked to prepare or tag a release, or to write or rewrite a release's
  notes.
---

# Release

A release runs from the previous `vMAJOR.MINOR.PATCH` tag to a `main` commit
whose CI passed; `mise run version` prints the checkout's versions.

## Find what changed

PR titles and descriptions show where to look, but confirm each change in the
code at the two endpoints, since a description can be stale.

- The Kotlin API: diff `lib/*/api/`, and read the declarations and
  implementation where a dump doesn't explain a behavior change.
- Artifacts and platforms: diff module build files, publishing conventions, and
  documented platform support.
- The native engine: the `maplibre-native-ffi` version in
  `gradle/libs.versions.toml`.
- The browser engine: the `third_party/maplibre-gl-js` pin and
  `patches/maplibre-gl-js/`.

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

Notes travel in the signed tag. Its message is `MapLibre Compose <version>`, a
blank line, then the notes, and release CI copies the body into a GitHub release
after Maven publication succeeds. End the file with a newline, or the signature
fuses onto the last line:

```sh
git tag -s --cleanup=verbatim -F <file> v<version> <commit>
```

Push it only when the maintainer says to. For a release that is already
published, edit its GitHub release notes instead, and leave the tag unchanged.
