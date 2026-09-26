import { init, use, type ECharts } from "echarts/core";
import { metricReference, appendReferenceDetails } from "./references";
import { LineChart } from "echarts/charts";
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  AriaComponent,
} from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { panels, format, delta, type Entry, type Summary } from "./model";

use([
  LineChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  MarkLineComponent,
  AriaComponent,
  CanvasRenderer,
]);
const colors = ["#5167c9", "#9866bd", "#258783"];

export class Charts {
  private charts: ECharts[] = [];
  private resize = new ResizeObserver(() => this.charts.forEach((chart) => chart.resize()));
  private zoom = [0, 100];
  private hiddenSeries = new Set<string>();
  constructor(
    private host: HTMLElement,
    private choose: (commit: string) => void,
    private distribution: (metric: string) => void,
  ) {
    this.resize.observe(host);
  }
  resetZoom() {
    this.zoom = [0, 100];
    this.charts.forEach((chart) => chart.dispatchAction({ type: "dataZoom", start: 0, end: 100 }));
  }
  clear() {
    this.charts.forEach((chart) => chart.dispose());
    this.charts = [];
    this.host.replaceChildren();
  }
  render(
    entries: Entry[],
    summaries: Map<string, Summary>,
    view: string,
    selected: Entry,
    baseline: Entry | null,
    releases: boolean,
  ) {
    this.clear();
    const ordered = [...entries].sort(
      (a, b) => Date.parse(a.commitDate) - Date.parse(b.commitDate),
    );
    const baselineSummary = baseline ? summaries.get(baseline.commit) : null;
    const start = Date.parse(ordered[0].commitDate);
    const end = Date.parse(ordered.at(-1)!.commitDate);
    const inspection = document.createElement("div");
    inspection.className = "inspection";
    const mode = document.createElement("span");
    mode.className = "inspection-mode";
    const timestamp = document.createElement("time");
    const sha = document.createElement("span");
    sha.className = "code";
    const subject = document.createElement("span");
    subject.className = "inspection-subject";
    const tags = document.createElement("span");
    tags.className = "inspection-tags";
    inspection.append(mode, timestamp, sha, subject, tags);
    this.host.append(inspection);
    const readouts: ((summary: Summary) => void)[] = [];
    const inspect = (entry: Entry, hovering: boolean) => {
      const summary = summaries.get(entry.commit);
      if (!summary) return;
      inspection.dataset.commit = entry.commit;
      mode.textContent = hovering ? "Hover" : "Selected";
      timestamp.dateTime = entry.commitDate;
      timestamp.textContent =
        new Intl.DateTimeFormat("en", {
          month: "short",
          day: "numeric",
          hour: "2-digit",
          minute: "2-digit",
          timeZone: "UTC",
        }).format(new Date(entry.commitDate)) + " UTC";
      sha.textContent = entry.commit.slice(0, 8);
      subject.textContent = entry.title;
      subject.title = entry.title;
      tags.textContent = entry.tags.join(", ");
      readouts.forEach((update) => update(summary));
      this.charts.forEach((chart) =>
        chart.dispatchAction(
          {
            type: "updateAxisPointer",
            currTrigger: hovering ? "mousemove" : "leave",
            x: chart.convertToPixel({ xAxisIndex: 0 }, Date.parse(entry.commitDate)),
            y: chart.getHeight() / 2,
          },
          { silent: true },
        ),
      );
    };
    const candidates = ordered.filter((entry) => summaries.has(entry.commit));
    const nearest = (time: number) =>
      candidates.reduce((a, b) =>
        Math.abs(Date.parse(a.commitDate) - time) < Math.abs(Date.parse(b.commitDate) - time)
          ? a
          : b,
      );
    for (const panel of panels[view]) {
      const reference = metricReference(panel.distribution ?? panel.keys[0]);
      const card = document.createElement("section");
      card.className = "panel";
      const heading = document.createElement("div");
      heading.className = "panel-heading";
      const title = document.createElement("h2");
      title.textContent = panel.title;
      const unit = document.createElement("span");
      unit.className = "panel-unit";
      unit.textContent = panel.unit;
      heading.append(title, unit);
      const values = document.createElement("div");
      values.className = "panel-values";
      panel.keys.forEach((key, i) => {
        const value = document.createElement("button");
        value.type = "button";
        value.className = "series-value";
        value.style.setProperty("--series-color", colors[i]);
        const seriesId = `${panel.title}:${key}`;
        value.setAttribute("aria-pressed", String(!this.hiddenSeries.has(seriesId)));
        value.setAttribute("aria-label", `Show ${panel.labels[i]} in ${panel.title}`);
        const label = document.createElement("span");
        label.textContent = panel.labels[i];
        const number = document.createElement("b");
        value.append(label, number);
        const change = document.createElement("span");
        change.className = "change";
        change.title = "Change from baseline";
        if (baselineSummary) value.append(change);
        readouts.push((summary) => {
          number.textContent = format(summary[key], panel.unit === "ratio" ? 3 : 1);
          if (baselineSummary)
            change.textContent = `(${delta(summary[key], baselineSummary[key], panel.unit === "ratio" ? 3 : 1)})`;
        });
        value.addEventListener("click", () => {
          const enabled = value.getAttribute("aria-pressed") !== "true";
          value.setAttribute("aria-pressed", String(enabled));
          if (enabled) this.hiddenSeries.delete(seriesId);
          else this.hiddenSeries.add(seriesId);
          chart.dispatchAction({ type: "legendToggleSelect", name: panel.labels[i] });
        });
        values.append(value);
      });
      const container = document.createElement("div");
      container.className = "chart";
      container.setAttribute("role", "img");
      container.setAttribute(
        "aria-label",
        `${panel.title}. ${panel.description} Selected: ${panel.keys.map((key, i) => `${panel.labels[i]} ${format(summaries.get(selected.commit)![key])}`).join(", ")}. Use the snapshot selector to inspect each commit.`,
      );
      const tools = document.createElement("div");
      tools.className = "panel-tools";
      const definition = document.createElement("details");
      const definitionLabel = document.createElement("summary");
      definitionLabel.textContent = "Definition & reference";
      const description = document.createElement("p");
      description.textContent = panel.description;
      definition.append(definitionLabel, description);
      if (panel.distribution) {
        const percentiles = document.createElement("p");
        percentiles.textContent =
          "Median (p50), p90 and p99 describe the center and upper tail. Each is the nearest-rank value covering at least that share of the production population. Open Distribution for counts, p75, mean and the maximum.";
        definition.append(percentiles);
      }
      appendReferenceDetails(definition, reference);
      tools.append(definition);
      if (panel.distribution) {
        const open = document.createElement("button");
        open.textContent = "Distribution →";
        open.setAttribute("aria-label", `${panel.title} distribution`);
        open.addEventListener("click", () => this.distribution(panel.distribution!));
        tools.append(open);
      }
      const referenceLabel = document.createElement("p");
      referenceLabel.className = "reference-label";
      referenceLabel.textContent = reference.label;
      card.append(heading, values, container, referenceLabel, tools);
      this.host.append(card);
      const chart = init(container, null, { renderer: "canvas" });
      const marks = [
        ...(reference.threshold != null
          ? [
              {
                yAxis: reference.threshold,
                lineStyle: { color: "#a16b24", type: "dashed", width: 1 },
                label: { show: false },
              },
            ]
          : []),
        {
          xAxis: Date.parse(selected.commitDate),
          lineStyle: { color: "#47516a", type: "solid", width: 1 },
          label: { show: false },
        },
        ...(baseline
          ? [
              {
                xAxis: Date.parse(baseline.commitDate),
                lineStyle: { color: "#9866bd", type: "dashed", width: 1 },
                label: { show: false },
              },
            ]
          : []),
        ...(releases
          ? ordered
              .filter((entry) => entry.tags.length)
              .map((entry) => ({
                xAxis: Date.parse(entry.commitDate),
                name: entry.tags.join(", "),
                lineStyle: { color: "#c3c8d3", type: "dotted", width: 1 },
                label: {
                  show: false,
                },
              }))
          : []),
      ];
      chart.setOption({
        animation: false,
        color: colors,
        grid: { top: 12, right: 18, bottom: 28, left: 48 },
        legend: {
          show: false,
          selectedMode: true,
          selected: Object.fromEntries(
            panel.keys.map((key, i) => [
              panel.labels[i],
              !this.hiddenSeries.has(`${panel.title}:${key}`),
            ]),
          ),
        },
        xAxis: {
          type: "time",
          min: start === end ? start - 86_400_000 : start,
          max: start === end ? end + 86_400_000 : end,
          splitNumber: 4,
          axisLabel: { fontSize: 10, color: "#71798a", hideOverlap: true, formatter: "{MMM} {d}" },
          axisLine: { lineStyle: { color: "#dce0e6" } },
          splitLine: { show: false },
        },
        useUTC: true,
        yAxis: {
          type: "value",
          min: 0,
          max:
            reference.threshold == null
              ? undefined
              : (extent: { max: number }) =>
                  Math.ceil(Math.max(extent.max, reference.threshold!) * 1.1),
          axisLabel: {
            fontSize: 10,
            color: "#71798a",
            formatter: (value: number) =>
              Math.abs(value) >= 1000 ? `${format(value / 1000)}k` : format(value),
          },
          splitNumber: 3,
          splitLine: { lineStyle: { color: "#edf0f4" } },
        },
        tooltip: {
          trigger: "axis",
          showContent: false,
          axisPointer: { type: "line", snap: true, label: { show: false } },
        },
        dataZoom: [
          {
            type: "inside",
            start: this.zoom[0],
            end: this.zoom[1],
            zoomOnMouseWheel: "ctrl",
            moveOnMouseMove: "shift",
            moveOnMouseWheel: false,
          },
        ],
        series: panel.keys.map((key, i) => ({
          name: panel.labels[i],
          type: "line",
          showSymbol: ordered.length < 3,
          symbolSize: 5,
          lineStyle: { width: i === 0 ? 1.8 : 1.2, type: i === 2 ? "dotted" : "solid" },
          connectNulls: false,
          data: ordered.map((entry) => [
            Date.parse(entry.commitDate),
            summaries.get(entry.commit)?.[key] ?? null,
            entry.commit,
          ]),
          ...(i === 0 ? { markLine: { silent: true, symbol: "none", data: marks } } : {}),
        })),
      });
      chart.on("datazoom", (event: unknown) => {
        const e = event as {
          start?: number;
          end?: number;
          batch?: { start: number; end: number }[];
        };
        const item = e.batch?.[0] ?? e;
        if (item.start != null && item.end != null) {
          this.zoom = [item.start, item.end];
          this.charts
            .filter((other) => other !== chart)
            .forEach((other) =>
              other.dispatchAction(
                { type: "dataZoom", start: item.start, end: item.end },
                { silent: true },
              ),
            );
        }
      });
      const entryAt = (x: number) =>
        nearest(chart.convertFromPixel({ xAxisIndex: 0 }, x) as number);
      chart.getZr().on("mousemove", (event) => {
        if (chart.containPixel("grid", [event.offsetX, event.offsetY])) {
          inspect(entryAt(event.offsetX), true);
        } else {
          inspect(selected, false);
        }
      });
      chart.getZr().on("globalout", () => inspect(selected, false));
      chart.getZr().on("click", (event) => {
        if (chart.containPixel("grid", [event.offsetX, event.offsetY]))
          this.choose(entryAt(event.offsetX).commit);
      });
      this.charts.push(chart);
    }
    inspect(selected, false);
  }
}
