import type { Spacing, TrendChart, Timeline } from "./chart";
import { icon, type icons } from "./icons";
import { formatDate, isRelease, type Commit } from "./model";
import { $, commitLink, el, releaseLink, setSearchParams } from "./page";
import { define } from "./terms";

/** Ranges that end at the latest commit and reach back this many months. */
const periods = [
  { id: "1m", label: "Last month", months: 1 },
  { id: "3m", label: "Last 3 months", months: 3 },
  { id: "6m", label: "Last 6 months", months: 6 },
  { id: "1y", label: "Last year", months: 12 },
];

/** The range a dashboard opens with, unless its URL names another. */
const defaultRange = "6m";

/** A UTC date as an `<input type="date">` value. */
const dateValue = (time: number) => new Date(time).toISOString().slice(0, 10);

/**
 * The commit a dashboard shows: the sticky bar's navigation and label, the cursor shared by the
 * trend charts, and the `commit` query parameter. Also the range of the history the charts show and
 * how they space it: a preset in the `range` parameter, or custom dates as the `from` and `to`
 * commits, and `spacing`. [noun] names one point of the history in the labels, such as "commit" or
 * "measurement".
 */
export class CommitSelection {
  readonly timeline: Timeline;
  /** The charts that follow the selection and the hovered commit. */
  charts: TrendChart[] = [];
  selected = 0;
  private hovered: number | null = null;
  /** "all", a period's ID, a release tag, or "custom" for any other window. */
  private range = defaultRange;
  private readonly rangeSelect = $<HTMLSelectElement>("metrics-range-select");
  private readonly fromInput = $<HTMLInputElement>("metrics-range-from");
  private readonly toInput = $<HTMLInputElement>("metrics-range-to");

  constructor(
    private readonly noun: string,
    private readonly onSelect: () => void,
  ) {
    this.timeline = {
      commits: [],
      times: [],
      releases: [],
      spacing: new URLSearchParams(location.search).get("spacing") === "dates" ? "dates" : "commits",
      window: [0, 0],
      hover: (i) => {
        this.hovered = i;
        this.setCursor();
      },
      select: (i) => {
        if (i === this.selected) return;
        this.selected = i;
        if (this.reveal(i)) this.showWindow();
        this.show();
        onSelect();
      },
      zoom: (first, last) => this.zoom(first, last),
    };
    for (const nav of this.navigation) {
      const button = $<HTMLButtonElement>(nav.id);
      button.append(icon(nav.icon));
      button.addEventListener("click", () => {
        const target = nav.target();
        if (target != null) this.timeline.select(target);
      });
    }
    for (const button of this.spacingButtons)
      button.addEventListener("click", () => {
        this.timeline.spacing = button.dataset.spacing as Spacing;
        this.showWindow();
      });
    this.rangeSelect.addEventListener("change", () => {
      const range = this.rangeSelect.value;
      if (range === "custom") {
        // Custom dates start from the range in view.
        this.range = range;
        this.showRange();
        this.fromInput.focus();
        return;
      }
      const span = this.resolve(range);
      if (span) this.zoom(...span, range);
    });
    const day = 86_400_000;
    for (const input of [this.fromInput, this.toInput])
      input.addEventListener("change", () => {
        const { times } = this.timeline;
        const [from, to] = [Date.parse(this.fromInput.value), Date.parse(this.toInput.value) + day - 1];
        const first = times.findIndex((t) => t >= from);
        const last = times.findLastIndex((t) => t <= to);
        // Dates that hold fewer than two points put back the current range.
        if (first >= 0 && first < last) this.zoom(first, last);
        else this.showRange();
      });
  }

  private readonly spacingButtons = [...document.querySelectorAll<HTMLButtonElement>("[data-spacing]")];

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
    const params = new URLSearchParams(location.search);
    const find = (key: string) => commits.findIndex((c) => c.commit === params.get(key));
    const [linked, from, to] = [find("commit"), find("from"), find("to")];
    this.selected = linked < 0 ? this.last : linked;

    const releases = commits.flatMap((c) => c.tags.filter((tag) => /^v\d+\.\d+\.0$/.test(tag))).reverse();
    const option = (value: string, textContent: string) =>
      el("option", { value, textContent, disabled: value !== "custom" && !this.resolve(value) });
    this.rangeSelect.replaceChildren(
      option("all", "All history"),
      el("optgroup", { label: "Recent" }, ...periods.map((p) => option(p.id, p.label))),
      ...(releases.length ? [el("optgroup", { label: "Since release" }, ...releases.map((tag) => option(tag, `Since ${tag}`)))] : []),
      option("custom", "Custom dates"),
    );
    this.fromInput.min = this.toInput.min = dateValue(this.timeline.times[0]);
    this.fromInput.max = this.toInput.max = dateValue(this.timeline.times[this.last]);

    // A preset in the URL is kept even if the history grows; custom dates name their commits. A
    // history too sparse for the default range opens whole.
    const preset = params.get("range");
    const span = preset && this.resolve(preset);
    const custom: [number, number] = [Math.max(0, from), to < 0 ? this.last : to];
    const fallback = this.resolve(defaultRange);
    if (span) [this.range, this.timeline.window] = [preset, span];
    else if (custom[0] < custom[1] && (from >= 0 || to >= 0)) [this.range, this.timeline.window] = ["custom", custom];
    else if (fallback) [this.range, this.timeline.window] = [defaultRange, fallback];
    else [this.range, this.timeline.window] = ["all", [0, this.last]];
    this.reveal(this.selected);
    this.showRange();
  }

  /** The window a preset range covers, or null when it holds fewer than two points. */
  private resolve(range: string): [number, number] | null {
    const { commits, times } = this.timeline;
    if (range === "all") return this.last > 0 ? [0, this.last] : null;
    const period = periods.find((p) => p.id === range);
    let first: number;
    if (period) {
      // The same day of an earlier month, or its last day when that month is shorter.
      const end = new Date(times[this.last]);
      const start = new Date(Date.UTC(end.getUTCFullYear(), end.getUTCMonth() - period.months, 1));
      const days = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth() + 1, 0)).getUTCDate();
      start.setUTCDate(Math.min(end.getUTCDate(), days));
      first = times.findIndex((t) => t >= start.getTime());
    } else {
      first = commits.findIndex((c) => c.tags.includes(range));
    }
    return first >= 0 && first < this.last ? [first, this.last] : null;
  }

  /**
   * Shows [first] through [last] in the charts, moving the selection into them. [range] names the
   * preset the window came from; any other window is custom, unless it is the whole history.
   */
  zoom(first: number, last: number, range = "custom") {
    this.range = range === "custom" && first === 0 && last === this.last ? "all" : range;
    this.timeline.window = [first, last];
    this.showWindow();
    const selected = Math.min(last, Math.max(first, this.selected));
    if (selected === this.selected) return;
    this.selected = selected;
    this.show();
    this.onSelect();
  }

  /** Moves the window over [index] if it is outside, keeping its width. Returns whether it moved. */
  private reveal(index: number) {
    const [first, last] = this.timeline.window;
    if (index >= first && index <= last) return false;
    const shift = index < first ? index - first : index - last;
    this.timeline.window = [first + shift, last + shift];
    this.range = "custom";
    return true;
  }

  private showWindow() {
    this.charts.forEach((chart) => chart.draw());
    this.showRange();
  }

  /** Renders the range and spacing controls and records them in the URL. */
  private showRange() {
    const { commits, times, spacing } = this.timeline;
    const [first, last] = this.timeline.window;
    const custom = this.range === "custom";
    for (const button of this.spacingButtons) button.setAttribute("aria-checked", String(button.dataset.spacing === spacing));
    this.rangeSelect.value = this.range;
    this.fromInput.value = dateValue(times[first]);
    this.toInput.value = dateValue(times[last]);
    setSearchParams({
      spacing: spacing === "dates" ? spacing : null,
      range: custom || this.range === defaultRange ? null : this.range,
      from: custom && first > 0 ? commits[first].commit : null,
      to: custom && last < this.last ? commits[last].commit : null,
    });
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
