import { format, formatCompact, formatDate, type Commit } from "./model";

export interface ChartSpec {
  title: string;
  unit: string;
  series: { key: string; label: string }[];
}

export interface Timeline {
  commits: Commit[];
  times: number[];
  releases: { index: number; label: string }[];
  hover(index: number | null): void;
  select(index: number): void;
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

/** A round step size that splits [0, max] into about three bands. */
function niceStep(max: number) {
  if (max <= 0) return 1;
  const rough = max / 3;
  const magnitude = 10 ** Math.floor(Math.log10(rough));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * magnitude).find((s) => s >= rough)!;
  return Math.max(step, 1);
}

/** Week, month, or year boundaries, whichever gives at most [limit] ticks. */
function dateTicks(start: number, end: number, limit: number) {
  const day = 86_400_000;
  const steps = [
    { days: 7 },
    { days: 14 },
    { months: 1 },
    { months: 3 },
    { months: 6 },
    { months: 12 },
  ];
  for (const step of steps) {
    const ticks: { time: number; label: string }[] = [];
    const date = new Date(start);
    date.setUTCHours(0, 0, 0, 0);
    if (step.days) {
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
    if (ticks.length <= limit) return ticks;
  }
  return [];
}

/** A step line per series over commit time, with release markers and a shared cursor. */
export class TrendChart {
  readonly element = document.createElement("figure");
  private readonly plot = svg("svg", { class: "metrics-plot" });
  private readonly cursor = svg("g");
  private readonly tooltip = document.createElement("div");
  private readonly values: HTMLElement[] = [];
  private columns: (number | null)[][] = [];
  private width = 0;
  private x = (_: number) => 0;
  private y = (_: number) => 0;
  private hovered: number | null = null;
  private selected = 0;

  constructor(
    private readonly spec: ChartSpec,
    private readonly timeline: Timeline,
  ) {
    this.element.className = "metrics-chart";
    const caption = document.createElement("figcaption");
    const title = document.createElement("span");
    title.className = "metrics-chart-title";
    title.textContent = spec.title;
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
      item.append(swatch, `${series.label} `, value);
      legend.append(item);
    });

    this.tooltip.className = "metrics-tooltip";
    this.tooltip.hidden = true;
    this.plot.setAttribute("tabindex", "0");
    this.plot.setAttribute("role", "img");
    this.plot.setAttribute("aria-label", `${spec.title} over time`);
    this.plot.addEventListener("pointermove", (event) => {
      const box = this.plot.getBoundingClientRect();
      this.timeline.hover(this.nearest(event.clientX - box.left));
    });
    this.plot.addEventListener("pointerleave", () => this.timeline.hover(null));
    this.plot.addEventListener("click", (event) => {
      const box = this.plot.getBoundingClientRect();
      this.timeline.select(this.nearest(event.clientX - box.left));
    });
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

  setData(columns: (number | null)[][]) {
    this.columns = columns;
    this.draw();
  }

  setCursor(hovered: number | null, selected: number) {
    this.hovered = hovered;
    this.selected = selected;
    this.drawCursor();
  }

  private nearest(px: number) {
    const { times } = this.timeline;
    let best = 0;
    for (let i = 1; i < times.length; i++) {
      if (Math.abs(this.x(times[i]) - px) < Math.abs(this.x(times[best]) - px)) best = i;
    }
    return best;
  }

  private draw() {
    const { times, releases } = this.timeline;
    if (!this.width || !times.length) return;
    const right = this.width - margin.right;
    const bottom = height - margin.bottom;
    const [start, end] = [times[0], times.at(-1)!];
    this.x = (t) => margin.left + ((t - start) / (end - start || 1)) * (right - margin.left);
    const max = Math.max(0, ...this.columns.flat().filter((v): v is number => v != null));
    const step = niceStep(max);
    const top = Math.max(step, Math.ceil(max / step) * step);
    this.y = (v) => bottom - (v / top) * (bottom - margin.top);

    const plot = this.plot;
    plot.replaceChildren();
    plot.setAttribute("viewBox", `0 0 ${this.width} ${height}`);
    plot.setAttribute("height", String(height));

    for (let v = 0; v <= top; v += step) {
      plot.append(svg("line", { class: "metrics-grid", x1: margin.left, x2: right, y1: this.y(v), y2: this.y(v) }));
      plot.append(
        svg("text", { class: "metrics-axis", x: margin.left - 6, y: this.y(v), "text-anchor": "end", "dominant-baseline": "middle" }, formatCompact(v)),
      );
    }

    // Dates along the bottom; releases along the top.
    for (const tick of dateTicks(start, end, Math.max(2, Math.floor((right - margin.left) / 80)))) {
      plot.append(svg("text", { class: "metrics-axis", x: this.x(tick.time), y: height - 6, "text-anchor": "middle" }, tick.label));
    }
    let lastLabel = -Infinity;
    for (const release of releases) {
      const x = this.x(times[release.index]);
      plot.append(svg("line", { class: "metrics-release", x1: x, x2: x, y1: margin.top - 4, y2: bottom }));
      if (x - lastLabel < 44) continue;
      lastLabel = x;
      plot.append(svg("text", { class: "metrics-axis", x, y: margin.top - 8, "text-anchor": "middle" }, release.label));
    }

    this.columns.forEach((column, i) => {
      let d = "";
      let open = false;
      column.forEach((value, j) => {
        if (value == null) {
          open = false;
          return;
        }
        const [x, y] = [this.x(times[j]), this.y(value)];
        d += open ? `H${x}V${y}` : `M${x} ${y}`;
        open = true;
      });
      if (open) d += `H${right}`;
      plot.append(svg("path", { class: `metrics-line metrics-series-${i + 1}`, d }));
    });
    plot.append(this.cursor);
    this.drawCursor();
  }

  private drawCursor() {
    const index = this.hovered ?? this.selected;
    this.columns.forEach((column, i) => (this.values[i].textContent = format(column[index])));
    if (!this.width) return;
    const { times, commits } = this.timeline;
    const x = this.x(times[index]);
    this.cursor.replaceChildren(
      svg("line", {
        class: this.hovered == null ? "metrics-selected" : "metrics-hover",
        x1: x,
        x2: x,
        y1: margin.top - 4,
        y2: height - margin.bottom,
      }),
    );
    if (this.hovered != null && this.hovered !== this.selected) {
      const s = this.x(times[this.selected]);
      this.cursor.prepend(svg("line", { class: "metrics-selected", x1: s, x2: s, y1: margin.top - 4, y2: height - margin.bottom }));
    }
    this.columns.forEach((column, i) => {
      const value = column[index];
      if (value != null)
        this.cursor.append(svg("circle", { class: `metrics-dot metrics-series-${i + 1}`, cx: x, cy: this.y(value), r: 4 }));
    });

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
