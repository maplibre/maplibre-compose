# 06: Reuse StyleComposition across map consumers

**What to build:** Add a reusable StyleComposition value that each map evaluates
in an independent Compose composition. Reconcile each complete immutable
revision while preserving native state and replaying Web state across
presentation loss.

**Blocked by:** 01: Separate reusable style definitions from loaded styles; 04:
Retain native engine maps between presentations; 05: Replay Web maps between
presentations

**Status:** ready-for-agent

- [ ] One StyleComposition definition can be supplied to two maps.
- [ ] Each consumer receives independent remember state and effects.
- [ ] Shared application state enters each evaluator through hoisted inputs.
- [ ] An evaluator publishes a complete immutable ordered style revision.
- [ ] A desired revision contains no engine map, live binding, or mutable
      definition.
- [ ] Detachment disposes the evaluator without removing the last applied native
      revision.
- [ ] Web replay applies the last desired revision to the replacement map.
- [ ] Reattachment evaluates current external state before the first visible
      frame.
- [ ] A base-style reload reconciles the complete latest revision.
