import type { TrendChart, Timeline } from "./chart";
import { icon, type icons } from "./icons";
import { formatDate, isRelease, type Commit } from "./model";
import { $, commitLink, releaseLink, setSearchParams } from "./page";
import { define } from "./terms";

/**
 * The commit a dashboard shows: the sticky bar's navigation and label, the cursor shared by the
 * trend charts, and the `commit` query parameter. [noun] names one point of the history in the
 * labels, such as "commit" or "measurement".
 */
export class CommitSelection {
  readonly timeline: Timeline;
  /** The charts that follow the selection and the hovered commit. */
  charts: TrendChart[] = [];
  selected = 0;
  private hovered: number | null = null;

  constructor(
    private readonly noun: string,
    onSelect: () => void,
  ) {
    this.timeline = {
      commits: [],
      times: [],
      releases: [],
      hover: (i) => {
        this.hovered = i;
        this.setCursor();
      },
      select: (i) => {
        if (i === this.selected) return;
        this.selected = i;
        this.show();
        onSelect();
      },
    };
    for (const nav of this.navigation) {
      const button = $<HTMLButtonElement>(nav.id);
      button.append(icon(nav.icon));
      button.addEventListener("click", () => {
        const target = nav.target();
        if (target != null) this.timeline.select(target);
      });
    }
  }

  get commits() {
    return this.timeline.commits;
  }

  private get last() {
    return this.commits.length - 1;
  }

  /** Replaces the history and selects the commit the URL names, or the latest one. */
  setCommits(commits: Commit[]) {
    this.timeline.commits = commits;
    this.timeline.times = commits.map((c) => Date.parse(c.date));
    this.timeline.releases = commits.flatMap((c, i) => c.tags.filter(isRelease).map((label) => ({ index: i, label })));
    this.hovered = null;
    const linked = commits.findIndex((c) => c.commit === new URLSearchParams(location.search).get("commit"));
    this.selected = linked < 0 ? this.last : linked;
  }

  /** Renders the selection: its label, the navigation, the chart cursors, and the URL. */
  show() {
    const commit = this.commits[this.selected];
    const release = commit.tags.find(isRelease);
    $("metrics-selection-label").replaceChildren(
      ...(release ? ["Release ", releaseLink(release)] : [`${this.selected === this.last ? "Latest" : "Selected"} ${this.noun}`]),
      `, ${formatDate(commit.date)}`,
    );
    $("metrics-selection-commit").replaceChildren(commitLink(commit.commit));
    $("metrics-selection-title").textContent = commit.title;
    for (const nav of this.navigation) {
      const button = $<HTMLButtonElement>(nav.id);
      const target = nav.target();
      button.disabled = target == null;
      button.ariaLabel = nav.label(target);
      define(button, button.ariaLabel);
    }
    this.setCursor();
    setSearchParams({ commit: this.selected === this.last ? null : commit.commit });
  }

  /** The release before the selection that the tiles compare against, or the first commit. */
  baseline() {
    for (let j = this.selected - 1; j >= 0; j--) {
      const release = this.commits[j].tags.find(isRelease);
      if (release) return { index: j, label: `since ${release}` };
    }
    return { index: 0, label: `since ${formatDate(this.commits[0].date)}` };
  }

  private setCursor() {
    this.charts.forEach((chart) => chart.setCursor(this.hovered, this.selected));
  }

  private readonly releaseIndices = () => this.commits.flatMap((c, i) => (c.tags.some(isRelease) ? [i] : []));
  private readonly releaseOf = (i: number) => this.commits[i].tags.find(isRelease);
  private readonly navigation: {
    id: string;
    icon: keyof typeof icons;
    target(): number | null;
    label(target: number | null): string;
  }[] = [
    {
      id: "metrics-previous-release",
      icon: "skipPrevious",
      target: () => this.releaseIndices().findLast((i) => i < this.selected) ?? null,
      label: (i) => (i == null ? "No earlier release" : `Previous release: ${this.releaseOf(i)}`),
    },
    {
      id: "metrics-previous",
      icon: "chevronLeft",
      target: () => (this.selected > 0 ? this.selected - 1 : null),
      label: (i) => (i == null ? `No earlier ${this.noun}` : `Previous ${this.noun}`),
    },
    {
      id: "metrics-next",
      icon: "chevronRight",
      target: () => (this.selected < this.last ? this.selected + 1 : null),
      label: (i) => (i == null ? `No later ${this.noun}` : `Next ${this.noun}`),
    },
    {
      id: "metrics-next-release",
      icon: "skipNext",
      // Past the last release, this goes to the latest commit.
      target: () => this.releaseIndices().find((i) => i > this.selected) ?? (this.selected < this.last ? this.last : null),
      label: (i) =>
        i == null ? `No later ${this.noun}` : this.releaseOf(i) ? `Next release: ${this.releaseOf(i)}` : `Latest ${this.noun}`,
    },
  ];
}
