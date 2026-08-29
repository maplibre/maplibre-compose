# 10: Migrate demos, documentation, and platform tests

**What to build:** Convert every example and platform-facing test to the new map
ownership API so that users and maintainers see one consistent integration
model.

**Blocked by:** 06: Reuse StyleComposition across map consumers; 07: Put
viewport-bound behavior on MapPresentation; 08: Separate desktop presentation
hosts from runtimes

**Status:** ready-for-agent

- [ ] Every demo creates or remembers MapState through MapRuntime.
- [ ] Demo style content uses reusable StyleComposition values.
- [ ] Viewport-dependent demo behavior uses the current MapPresentation.
- [ ] Documentation and compiled snippets show only the new public API.
- [ ] Native platform tests cover retained presentations through the new API.
- [ ] Browser tests cover recreation and replay through the new API.
- [ ] Android, iOS, Desktop, and Web demos build successfully.
