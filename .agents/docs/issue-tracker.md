# Issue tracker: local Markdown

Agent-facing implementation specs and issues for this repository live under
`.agents/scratch/`. GitHub Issues remains the human-facing project tracker. The
engineering skills use the local files and do not publish their tickets to
GitHub Issues.

## Conventions

- Each feature uses `.agents/scratch/<feature-slug>/`.
- The feature specification is `.agents/scratch/<feature-slug>/spec.md`.
- Each implementation issue is
  `.agents/scratch/<feature-slug>/issues/<NN>-<slug>.md`.
- Issue numbers start at `01`.
- A `Status:` line near the top records the triage state.
- A `Blocked by: NN, NN` line records dependencies.
- Comments append under a `## Comments` heading.

When a skill publishes to the issue tracker, it creates a file under the
applicable feature directory.

When a skill fetches a ticket, it reads the referenced issue file.

## Wayfinding operations

- The map is `.agents/scratch/<effort>/map.md`.
- Child tickets use `.agents/scratch/<effort>/issues/<NN>-<slug>.md`.
- A `Type:` line records `research`, `prototype`, `grilling`, or `task`.
- A ticket is unblocked when every issue listed in `Blocked by:` has
  `Status: resolved`.
- The next ticket is the first numbered open, unblocked, and unclaimed issue.
- Claiming a ticket changes its status to `claimed`.
- Resolving a ticket adds an `## Answer` section and changes its status to
  `resolved`.
