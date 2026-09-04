# AI Policy

Follow
[MapLibre's AI Policy](https://github.com/maplibre/maplibre/blob/main/AI_POLICY.md)
and the guidance below for this repository.

## Stay in the loop

Before you mark a pull request ready for review:

- Read every line you are asking maintainers to merge.
- Be able to explain the change, how it fits the codebase, and how you validated
  it—without leaning on the tool to answer review questions.
- Review the PR description for accuracy. You may use AI to draft it; you are
  responsible for every claim.

Design the change. Use AI to draft, explore, or speed up typing—not to replace
understanding the problem or the existing code.

## Talk to maintainers in your own voice

Issues and review replies are a conversation between humans. Write them from
your own understanding.

- State problems and proposals in your own words.
- When a maintainer asks a question, answer from your understanding. Do not
  paste model output as your reply.
- Trim verbosity. Say what matters for the review.

If you used AI to polish English, read the result once and adjust it so it
sounds like you. For translation, writing in your native language and adding an
English version in a quote block works well.

AI-assisted PR descriptions may use the space needed to explain the change. Keep
them concise and specific; a sentence limit is not required. Use draft status
while implementation, decisions, or your review of generated code are pending.
An agent may open or mark the PR ready after you confirm that you have reviewed
the completed contribution.

## When AI context belongs in a thread

Sometimes a snippet from an AI session helps reviewers (for example, a design
option you rejected). Share it in a way that keeps the thread readable:

- **A few lines:** Put them in a quote block (`>`), label them as AI-generated,
  and add a short note in your own words on why they are relevant.
- **More than a few lines:** Put the full text in a
  [GitHub Gist](https://gist.github.com/) (or similar) and link it. In the
  comment, summarize in your own words what the gist contains and what you want
  reviewers to take from it.

Do not post long, unedited AI output in issues or pull requests without
maintainer approval.

## Disclosure

When disclosure applies under MapLibre's policy, fill in the **AI assistance**
section of the pull request template. Disclosure is not penalized.

When planning docs guided the work, commit them on your branch as you go.
Removing them before merge is fine; reviewers can still follow the process in
the commit history.

## Credits

Practices in this document were informed by the AI policies of
[uv](https://github.com/astral-sh/.github/blob/main/AI_POLICY.md),
[ripgrep](https://github.com/BurntSushi/ripgrep/blob/master/AI_POLICY.md), and
[Ghostty](https://github.com/ghostty-org/ghostty/blob/main/AI_POLICY.md).
