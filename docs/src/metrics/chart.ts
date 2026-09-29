import { format, formatCompact, formatDate, type Commit } from "./model";
import { define, term } from "./terms";

export interface ChartSpec {
  title: string;
  unit: string;
  definition?: string;
  series: { key: string; label: string; definition?: string }[];
  /** Whether values are whole numbers; a fractional axis needs decimals. Defaults to true. */
  integer?: boolean;
  /** Formats a value for the legend and tooltip. Defaults to whole numbers. */
  format?: (value: number | null | undefined) => string;
}

/** The low and high column drawn as a band behind one series. */
export type Band = [(number | null)[], (number | null)[]];

/** How the x-axis spaces commits: one step per commit, or by commit date. */
export type Spacing = "commits" | "dates";

export interface Timeline {
  commits: Commit[];
  times: number[];
  releases: { index: number; label: string }[];
  spacing: Spacing;
  /** The first and last index of the commits the charts show. */
  window: [number, number];
  hover(index: number | null): void;
  select(index: number): void;
  zoom(first: number, last: number): void;
}

const height = 168;
const margin = { top: 22, right: 8, bottom: 22, left: 40 };
const svgNs = "http://www.w3.org/2000/svg";

function svg<K extends keyof SVGElementTagNameMap>(
  tag: K,
  attributes: Record<string, string | number> = {},
  text?: string,
) {
  const element = document.createElementNS(svgNs, tag);
  for (const [name, value] of Object.entries(attributes)) element.setAttribute(name, String(value));
  if (text !== undefined) element.textContent = text;
  return element;
}

/** A round step size that splits [range] into about three bands. */
function niceStep(range: number, integer: boolean) {
  if (range <= 0) return 1;
  const rough = range / 3;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= rough)!;
  return integer ? Math.max(step, 1) : step;
}

type Step = { days: number; months?: undefined } | { months: number; days?: undefined };

/** Every [step] boundary from [start] to [end]: midnights, Mondays, or the first of a month. */
function boundaries(start: number, end: number, step: Step) {
  const day = 86_400_000;
  const ticks: { time: number; label: string }[] = [];
  const date = new Date(start);
  date.setUTCHours(0, 0, 0, 0);
  if (step.days === 1) {
    date.setUTCDate(date.getUTCDate() + 1);
  } else if (step.days) {
    // Mondays.
    date.setUTCDate(date.getUTCDate() + ((8 - date.getUTCDay()) % 7 || 7));
  } else {
    date.setUTCDate(1);
    date.setUTCMonth(Math.ceil((date.getUTCMonth() + 1) / step.months!) * step.months!);
  }
  while (date.getTime() <= end) {
    const label = date.toLocaleDateString("en", {
      timeZone: "UTC",
      ...(step.days
        ? { month: "short", day: "numeric" }
        : date.getUTCMonth() === 0
          ? { year: "numeric" }
          : { month: "short" }),
    });
    ticks.push({ time: date.getTime(), label });
    if (step.days) date.setTime(date.getTime() + step.days * day);
    else date.setUTCMonth(date.getUTCMonth() + step.months!);
  }
  return ticks;
}

/** Evenly spaced boundaries, the finest that give at most [limit] ticks. */
function dateTicks(start: number, end: number, limit: number) {
  const steps: Step[] = [{ days: 1 }, { days: 7 }, { days: 14 }, { months: 1 }, { months: 3 }, { months: 6 }, { months: 12 }];
  for (const step of steps) {
    const ticks = boundaries(start, end, step);
    if (ticks.length <= limit) return ticks;
  }
  return [];
}

/**
 * Years, then months, weeks, and days, each where it fits between coarser ones. Evenly spaced
 * commits crowd dates in quiet periods and spread them in busy ones, so each period gets the
 * finest labels that fit.
 */
function adaptiveTicks(start: number, end: number, x: (time: number) => number, gap: number) {
  const steps: Step[] = [{ months: 12 }, { months: 1 }, { days: 7 }, { days: 1 }];
  const placed: { x: number; label: string }[] = [];
  const seen = new Set<number>();
  for (const step of steps) {
    for (const tick of boundaries(start, end, step)) {
      if (seen.has(tick.time)) continue;
      seen.add(tick.time);
      const at = x(tick.time);
      if (placed.every((p) => Math.abs(p.x - at) >= gap)) placed.push({ x: at, label: tick.label });
    }
  }
  return placed;
}

/**
 * A line per series over the timeline's window, with release markers and a shared cursor. A click
 * selects a commit; a drag across the plot zooms every chart to the commits it covers.
 */
export class TrendChart {
  readonly element = document.createElement("figure");
  private readonly plot = svg("svg", { class: "metrics-plot" });
  private readonly cursor = svg("g");
  private readonly brush = svg("rect", { class: "metrics-brush" });
  private readonly tooltip = document.createElement("div");
  private readonly values: HTMLElement[] = [];
  private columns: (number | null)[][] = [];
  private bands: (Band | undefined)[] = [];
  private readonly format: (value: number | null | undefined) => string;
  private width = 0;
  /** The x position of a commit index. */
  private x = (_: number) => 0;
  private y = (_: number) => 0;
  private hovered: number | null = null;
  private selected = 0;
  private drag: { pointer: number; x: number; moved: boolean } | null = null;

  constructor(
    private readonly spec: ChartSpec,
    private readonly timeline: Timeline,
  ) {
    this.element.className = "metrics-chart";
    this.format = spec.format ?? format;
    const caption = document.createElement("figcaption");
    const title = document.createElement("span");
    title.className = "metrics-chart-title";
    title.textContent = spec.title;
    if (spec.definition) define(title, spec.definition);
    const unit = document.createElement("span");
    unit.className = "metrics-muted";
    unit.textContent = spec.unit;
    caption.append(title, unit);

    const legend = document.createElement("ul");
    legend.className = "metrics-legend";
    spec.series.forEach((series, i) => {
      const item = document.createElement("li");
      const swatch = document.createElement("span");
      swatch.className = `metrics-swatch metrics-series-${i + 1}`;
      const value = document.createElement("strong");
      this.values.push(value);
      item.append(swatch, series.definition ? term(series.label, series.definition) : series.label, " ", value);
      legend.append(item);
    });

    this.tooltip.className = "metrics-tooltip";
    this.tooltip.hidden = true;
    this.plot.setAttribute("tabindex", "0");
    this.plot.setAttribute("role", "img");
    this.plot.setAttribute("aria-label", `${spec.title} over time`);
    const offset = (event: PointerEvent) => event.clientX - this.plot.getBoundingClientRect().left;
    this.plot.addEventListener("pointerdown", (event) => {
      if (event.button !== 0) return;
      this.drag = { pointer: event.pointerId, x: offset(event), moved: false };
      this.plot.setPointerCapture(event.pointerId);
    });
    this.plot.addEventListener("pointermove", (event) => {
      const x = offset(event);
      if (this.drag?.pointer === event.pointerId) {
        // A small movement during a click is still a click.
        this.drag.moved ||= Math.abs(x - this.drag.x) > 4;
        if (this.drag.moved) this.drawBrush(this.drag.x, x);
      }
      this.timeline.hover(this.nearest(x));
    });
    this.plot.addEventListener("pointerup", (event) => {
      const drag = this.drag;
      if (drag?.pointer !== event.pointerId) return;
      this.endDrag();
      const [from, to] = [this.nearest(drag.x), this.nearest(offset(event))];
      if (!drag.moved) this.timeline.select(to);
      else if (from !== to) this.timeline.zoom(Math.min(from, to), Math.max(from, to));
    });
    this.plot.addEventListener("pointercancel", () => this.endDrag());
    this.plot.addEventListener("pointerleave", () => this.timeline.hover(null));
    this.plot.addEventListener("keydown", (event) => {
      const last = this.timeline.commits.length - 1;
      const target = {
        ArrowLeft: this.selected - 1,
        ArrowRight: this.selected + 1,
        Home: 0,
        End: last,
      }[event.key];
      if (target === undefined) return;
      event.preventDefault();
      this.timeline.select(Math.min(last, Math.max(0, target)));
    });

    const body = document.createElement("div");
    body.className = "metrics-plot-area";
    body.append(this.plot, this.tooltip);
    this.element.append(caption, legend, body);
    new ResizeObserver(() => {
      const width = body.clientWidth;
      if (width && width !== this.width) {
        this.width = width;
        this.draw();
      }
    }).observe(body);
  }

  setData(columns: (number | null)[][], bands: (Band | undefined)[] = []) {
    this.columns = columns;
    this.bands = bands;
    this.draw();
  }

  setCursor(hovered: number | null, selected: number) {
    this.hovered = hovered;
    this.selected = selected;
    this.drawCursor();
  }

  /** The commit in the window closest to [px]. */
  private nearest(px: number) {
    const [first, last] = this.timeline.window;
    let best = first;
    for (let i = first + 1; i <= last; i++) {
      if (Math.abs(this.x(i) - px) < Math.abs(this.x(best) - px)) best = i;
    }
    return best;
  }

  private drawBrush(from: number, to: number) {
    const [left, right] = [Math.min(from, to), Math.max(from, to)].map((x) =>
      Math.min(this.width - margin.right, Math.max(margin.left, x)),
    );
    for (const [name, value] of Object.entries({
      x: left,
      y: margin.top - 4,
      width: right - left,
      height: height - margin.bottom - margin.top + 4,
    }))
      this.brush.setAttribute(name, String(value));
    if (!this.brush.isConnected) this.plot.append(this.brush);
  }

  private endDrag() {
    this.drag = null;
    this.brush.remove();
  }

  /**
   * The x position of the first commit in [first]..[last] on or after a time. Times usually come in
   * order, so the search continues from the previous answer, and starts over when a time is earlier.
   */
  private commitAt(first: number, last: number) {
    const { times } = this.timeline;
    let j = first;
    let previous = -Infinity;
    return (time: number) => {
      if (time < previous) j = first;
      previous = time;
      while (j < last && times[j] < time) j++;
      return this.x(j);
    };
  }

  /** Renders the data over the timeline's window and spacing. */
  draw() {
    const { times, releases, spacing } = this.timeline;
    const [first, last] = this.timeline.window;
    if (!this.width || !times.length) return;
    const right = this.width - margin.right;
    const bottom = height - margin.bottom;
    const position = (i: number) => (spacing === "dates" ? times[i] : i);
    const [start, end] = [position(first), position(last)];
    const scale = (p: number) => margin.left + ((p - start) / (end - start || 1)) * (right - margin.left);
    this.x = (i) => scale(position(i));

    // The axis spans the visible values rather than starting at zero, so a change of a few hundred
    // lines in 60,000 still shows.
    const integer = this.spec.integer ?? true;
    const visible = [...this.columns, ...this.bands.flatMap((band) => band ?? [])]
      .flatMap((column) => column.slice(first, last + 1))
      .filter((v): v is number => v != null);
    const [min, max] = visible.length ? [Math.min(...visible), Math.max(...visible)] : [0, 0];
    const step = niceStep(max - min || Math.abs(max), integer);
    // A flat line sits mid-axis, except at zero, which stays the baseline.
    const flat = min === max && min > 0;
    const floor = Math.floor(min / step + 1e-9) * step;
    const low = flat ? Math.max(0, floor - step) : floor;
    const high = Math.max(low + (flat ? 2 : 1) * step, Math.ceil(max / step - 1e-9) * step);
    // As many decimals as the step has, so 0.25 labels as 0.25 rather than 0.3.
    const decimals = integer ? 0 : (step.toString().split(".")[1] ?? "").length;
    const axisLabel = (v: number) => (integer ? formatCompact(v) : v.toLocaleString("en", { maximumFractionDigits: decimals }));
    this.y = (v) => bottom - ((v - low) / (high - low)) * (bottom - margin.top);

    const plot = this.plot;
    plot.replaceChildren();
    plot.setAttribute("viewBox", `0 0 ${this.width} ${height}`);
    plot.setAttribute("height", String(height));

    for (let i = 0; low + i * step <= high + 1e-9; i++) {
      const v = low + i * step;
      plot.append(svg("line", { class: "metrics-grid", x1: margin.left, x2: right, y1: this.y(v), y2: this.y(v) }));
      plot.append(
        svg("text", { class: "metrics-axis", x: margin.left - 6, y: this.y(v), "text-anchor": "end", "dominant-baseline": "middle" }, axisLabel(v)),
      );
    }

    // Dates along the bottom; releases along the top. With evenly spaced commits, a date sits at
    // the first commit on or after it.
    const ticks =
      spacing === "dates"
        ? dateTicks(start, end, Math.max(2, Math.floor((right - margin.left) / 80))).map((t) => ({ x: scale(t.time), label: t.label }))
        : adaptiveTicks(times[first], times[last], this.commitAt(first, last), 56);
    for (const tick of ticks) {
      plot.append(svg("text", { class: "metrics-axis", x: tick.x, y: height - 6, "text-anchor": "middle" }, tick.label));
    }
    let lastLabel = -Infinity;
    for (const release of releases) {
      if (release.index < first || release.index > last) continue;
      const x = this.x(release.index);
      plot.append(svg("line", { class: "metrics-release", x1: x, x2: x, y1: margin.top - 4, y2: bottom }));
      if (x - lastLabel < 44) continue;
      lastLabel = x;
      plot.append(svg("text", { class: "metrics-axis", x, y: margin.top - 8, "text-anchor": "middle" }, release.label));
    }

    // Bands connect consecutive ranges; isolated ranges keep a visible width.
    this.bands.forEach((band, i) => {
      const [low, high] = band ?? [];
      if (!low || !high) return;
      const runs: number[][] = [];
      for (let j = first; j <= last; j++) {
        if (high[j] == null || low[j] == null) continue;
        if (runs.length && runs.at(-1)!.at(-1) === j - 1) runs.at(-1)!.push(j);
        else runs.push([j]);
      }
      for (const xs of runs) {
        const tail = xs.at(-1)!;
        if (xs.length === 1) {
          plot.append(svg("rect", {
            class: `metrics-band metrics-series-${i + 1}`,
            x: this.x(tail) - 4,
            y: this.y(high[tail]!),
            width: 8,
            height: this.y(low[tail]!) - this.y(high[tail]!),
          }));
          continue;
        }
        let d = `M${this.x(xs[0])} ${this.y(high[xs[0]]!)}`;
        for (const j of xs.slice(1)) d += `L${this.x(j)} ${this.y(high[j]!)}`;
        d += `L${this.x(tail)} ${this.y(low[tail]!)}`;
        for (let k = xs.length - 2; k >= 0; k--) d += `L${this.x(xs[k])} ${this.y(low[xs[k]]!)}`;
        plot.append(svg("path", { class: `metrics-band metrics-series-${i + 1}`, d: `${d}Z` }));
      }
    });
    this.columns.forEach((column, i) => {
      let d = "";
      let open = false;
      for (let j = first; j <= last; j++) {
        const value = column[j];
        if (value == null) {
          open = false;
          continue;
        }
        const [x, y] = [this.x(j), this.y(value)];
        if ((j === first || column[j - 1] == null) && (j === last || column[j + 1] == null))
          plot.append(svg("circle", { class: `metrics-dot metrics-series-${i + 1}`, cx: x, cy: y, r: 4 }));
        d += open ? `L${x} ${y}` : `M${x} ${y}`;
        open = true;
      }
      plot.append(svg("path", { class: `metrics-line metrics-series-${i + 1}`, d }));
    });
    plot.append(this.cursor);
    this.drawCursor();
  }

  private drawCursor() {
    const index = this.hovered ?? this.selected;
    this.columns.forEach((column, i) => (this.values[i].textContent = this.format(column[index])));
    if (!this.width) return;
    const { commits } = this.timeline;
    const [first, last] = this.timeline.window;
    const shown = (i: number) => i >= first && i <= last;
    const x = this.x(index);
    const line = (at: number, className: string) =>
      svg("line", { class: className, x1: at, x2: at, y1: margin.top - 4, y2: height - margin.bottom });
    this.cursor.replaceChildren();
    if (this.hovered != null && this.hovered !== this.selected && shown(this.selected))
      this.cursor.append(line(this.x(this.selected), "metrics-selected"));
    if (shown(index)) {
      this.cursor.append(line(x, this.hovered == null ? "metrics-selected" : "metrics-hover"));
      this.columns.forEach((column, i) => {
        const value = column[index];
        if (value != null)
          this.cursor.append(svg("circle", { class: `metrics-dot metrics-series-${i + 1}`, cx: x, cy: this.y(value), r: 4 }));
      });
    }

    const commit = commits[index];
    this.tooltip.hidden = this.hovered == null || !this.element.matches(":hover");
    if (!this.tooltip.hidden) {
      this.tooltip.replaceChildren();
      const heading = document.createElement("div");
      heading.className = "metrics-muted";
      heading.textContent = `${formatDate(commit.date)} · ${commit.commit.slice(0, 7)}`;
      const title = document.createElement("div");
      title.textContent = commit.title;
      this.tooltip.append(heading, title);
      const flip = x > this.width / 2;
      this.tooltip.style.left = flip ? "" : `${x + 12}px`;
      this.tooltip.style.right = flip ? `${this.width - x + 12}px` : "";
    }
  }
}
