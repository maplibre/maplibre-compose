# Interaction cleanup

## Agreed scope

- Use Compose-style `onClick`, `onDoubleClick`, and `onLongClick` naming on
  layers; use corresponding click/doubleClick/longClick names in configuration.
  Long click combines touch long press and secondary mouse click. Keep physical
  binding names `longPress` and `secondaryClick`.
- Remove two-finger click callbacks from layers and map callbacks. Preserve
  `bindings.twoFingerTap` and its default zoom-out response.
- Keep ClickResult as the decision for map callback, rendered-feature, and
  camera fallback delivery. Clarify that it does not control Compose pointer
  propagation; recognizers use Compose consumption directly.
- Organize the existing module: map integration in `map`; public interaction
  configuration/events in `interaction`; recognition, arbitration,
  subscriptions, dispatch, and host adaptation in `interaction.internal`; camera
  values and commands in `camera`.
- Move the internal `input` recognizers into the interaction implementation. Do
  not carve out a module or invent a reusable engine.
- Replace redundant internal Map prefixes with responsibility-based names.
  Preserve useful public names such as MapInteractions and MapOptions.
- Make long input methods readable through meaningful paragraphs, coherent
  operations, and comments explaining thresholds, arbitration, cancellation, and
  first-delta adjustments. Avoid trivial wrappers, speculative helpers, and
  arbitrary line-count targets.
- Migrate consumers, snippets, reference links, and tests. Keep the high-level
  guide focused on app tasks.
- Keep tests for external behavior; avoid timing assumptions,
  implementation-pinning assertions, duplicated formulas, vacuous tests, and
  unnecessary coverage.

## Execution and completion

1. Record this plan before implementation.
2. Clean up callbacks and input readability, then reorganize packages and
   imports.
3. Compile library and demo consumers; run proportional
   desktop/browser/Android/iOS validation and documentation/static checks.
4. Run an independent adversarial review for incomplete cleanup, unnecessary
   abstractions, readability, package ownership, and regressions. Fix supported
   findings and repeat until no material findings remain.
5. Remove this temporary plan once all work is complete, per the user's earlier
   request to keep completed gesture plans out of .agents/docs.
6. Commit and push only after the cleanup and review loop. The user explicitly
   authorized pushing at completion; keep PR draft.

## Progress

- Plan recorded. Implementation pending.
