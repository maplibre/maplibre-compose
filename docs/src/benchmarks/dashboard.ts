import { TrendChart, type Band, type ChartSpec } from "../metrics/chart";
import type { Commit } from "../metrics/model";
import { $, el, fetchJson, setSearchParams, showBody } from "../metrics/page";
import { CommitSelection } from "../metrics/selection";
import { installTooltips, term } from "../metrics/terms";
import { formatPercent, formatValue, type Index, type Kind, type Scope, type Series } from "./model";

const definitions = {
  cpuPerOperation:
    "Process CPU time in the measured window, on every thread, divided by the operations the workload " +
    "submitted: per frame for frame-driven workloads, per update otherwise.",
  cpuPerSecond: "Process CPU time per second of the measured window, on every thread.",
  completion:
    "Time from starting an update until the case's completion signal: style readiness, a rendered-feature query, or map settlement. This does not confirm screen presentation.",
  submission:
    "Elapsed time to prepare and submit an update. This includes suspending preparation and is not UI-thread blocking time.",
  runtimeConstruction: "Time for the runtime constructor to return, reopening a primed empty local database in a warm process.",
  runtimeReadiness: "Time from starting runtime construction until offline readiness is observed on the main dispatcher. No map is created.",
  close: "Time for the first close call to return while the map is still presented, or while the runtime is open.",
  cleanup:
    "Time from starting close until presentation detachment and cleanup complete. Compose awaits native release; classic iOS observes view removal, not native destruction.",
  frameInterval: "Time between consecutive frame callbacks in the measured window.",
  startup:
    "Time from creating the map until its first fully rendered frame. The style, tiles, and glyphs " +
    "are packaged with the app.",
  classic:
    "The platform's classic MapLibre SDK running the same workload on the same device, back to back " +
    "with MapLibre Compose.",
  spread: "The band spans the lowest and highest repetition; the line is the median.",
  uiFrame: "Duration of the app window's frames in the measured window, from the platform's frame timing.",
  mapReturn:
    "Time from creating a map with 256 declared layers and 64 images until its content renders and " +
    "settles, with a 300 ms panel transition running at the same time.",
};

interface Metric {
  key: string;
  title: string;
  definition: string;
}

const metrics = {
  cpuPerFrame: { key: "cpu_ms_per_operation", title: "CPU per frame", definition: definitions.cpuPerOperation },
  cpuPerUpdate: { key: "cpu_ms_per_operation", title: "CPU per update", definition: definitions.cpuPerOperation },
  cpuPerSecond: { key: "cpu_ms_per_second", title: "Idle CPU per second", definition: definitions.cpuPerSecond },
  completion: { key: "completion_p50_ms", title: "Completion, median", definition: definitions.completion },
  submission: { key: "submission_p50_ms", title: "Submission, median", definition: definitions.submission },
  runtimeConstruction: { key: "submission_p50_ms", title: "Constructor return, median", definition: definitions.runtimeConstruction },
  runtimeReadiness: { key: "completion_p50_ms", title: "Readiness, median", definition: definitions.runtimeReadiness },
  close: { key: "close_p50_ms", title: "Close return, median", definition: definitions.close },
  cleanup: { key: "close_completion_p50_ms", title: "Cleanup completion, median", definition: definitions.cleanup },
  frameInterval: { key: "frame_interval_p95_ms", title: "Frame interval p95", definition: definitions.frameInterval },
  startup: { key: "startup_first_frame_ms", title: "Time to first frame", definition: definitions.startup },
  uiFrameP95: { key: "ui_frame_p95_ms", title: "Window frame p95", definition: definitions.uiFrame },
  uiFrameMax: { key: "ui_frame_max_ms", title: "Worst window frame", definition: definitions.uiFrame },
  returnTime: { key: "completion_p50_ms", title: "Map return time, median", definition: definitions.mapReturn },
} satisfies Record<string, Metric>;

const frameDriven = ["camera", "overlays", "padding", "resize", "recompose"];
const updates = ["source", "source-latency", "layers", "layout", "paint", "sparse-paint", "style", "style-overlay", "overlay-update", "images", "image-cycle", "image-preparation"];
const completedUpdates = ["source-latency", "layers", "layout", "style", "style-overlay", "overlay-update", "images", "image-cycle", "image-preparation"];

/**
 * Sections group charts by metric, so every chart in a section shares a unit and a meaning and
 * the case is the chart's title. An item's metric can list alternatives: the first one the device
 * reports is charted, so Android shows window frames where others show the frame interval.
 */
const sections: { title: string; items: { workloads: string[]; metric: Metric | Metric[] }[] }[] = [
  { title: "CPU per frame", items: [{ workloads: frameDriven, metric: metrics.cpuPerFrame }] },
  {
    title: "Frame pacing",
    items: [{ workloads: [...frameDriven, "sparse-paint"], metric: [metrics.uiFrameP95, metrics.frameInterval] }],
  },
  { title: "CPU per update", items: [{ workloads: updates, metric: metrics.cpuPerUpdate }] },
  { title: "Submission latency", items: [{ workloads: updates, metric: metrics.submission }] },
  { title: "Completion latency", items: [{ workloads: completedUpdates, metric: metrics.completion }] },
  {
    title: "Lifecycle",
    items: [
      { workloads: ["runtime-startup"], metric: metrics.runtimeConstruction },
      { workloads: ["runtime-startup"], metric: metrics.runtimeReadiness },
      { workloads: ["map-return"], metric: metrics.returnTime },
      { workloads: ["map-return"], metric: metrics.uiFrameMax },
      { workloads: ["map-return", "runtime-startup"], metric: metrics.close },
      { workloads: ["map-return", "runtime-startup"], metric: metrics.cleanup },
    ],
  },
  {
    title: "Startup and idle",
    items: [
      { workloads: ["idle"], metric: metrics.startup },
      { workloads: ["idle"], metric: metrics.cpuPerSecond },
    ],
  },
];

/** Metrics the publisher records with their spread across repetitions. */
const banded = new Set([
  "cpu_ms_per_operation",
  "cpu_ms_per_second",
  "completion_p50_ms",
  "submission_p50_ms",
  "close_p50_ms",
  "close_completion_p50_ms",
  "frame_interval_p95_ms",
  "ui_frame_p95_ms",
  "ui_frame_max_ms",
  "startup_first_frame_ms",
]);

interface Tile {
  label: string;
  definition: string;
  /** Alternatives; the first column the device reports is shown. */
  columns: string[];
  note: string;
}

const tiles: Tile[] = [
  { label: "Idle CPU", definition: definitions.cpuPerSecond, columns: ["idle-basemap.compose.cpu_ms_per_second"], note: "ms per second" },
  { label: "Camera frame", definition: definitions.cpuPerOperation, columns: ["camera-basemap.compose.cpu_ms_per_operation"], note: "ms of CPU per frame" },
  {
    label: "Map return",
    definition: definitions.mapReturn,
    columns: ["map-return.compose.ui_frame_max_ms", "map-return.compose.completion_p50_ms"],
    note: "ms, worst window frame",
  },
  { label: "Update latency", definition: definitions.completion, columns: ["source-completion.compose.completion_p50_ms"], note: "ms, median" },
  { label: "Startup", definition: definitions.startup, columns: ["idle-basemap.compose.startup_first_frame_ms"], note: "ms to first frame" },
];

/**
 * One device's measurements: the commits it measured, in order, and its series restricted to them.
 * Everything below works on this view, so every index has data and the shared timeline, chart,
 * and navigation code apply unchanged.
 */
interface View {
  commits: Commit[];
  series: Series;
}

function view(index: Index, series: Series): View {
  const measured = index.commits.flatMap((_, i) => (Object.values(series).some((column) => column[i] != null) ? [i] : []));
  // A release on a commit this device skipped marks its next measurement.
  const commits = measured.map((i, k) => {
    const from = k === 0 ? i : measured[k - 1] + 1;
    const tags = index.commits.slice(from, i + 1).flatMap((c) => c.tags);
    return { ...index.commits[i], tags };
  });
  return {
    commits,
    series: Object.fromEntries(Object.entries(series).map(([key, column]) => [key, measured.map((i) => column[i] ?? null)])),
  };
}

export async function start() {
  const root = document.querySelector<HTMLElement>(".metrics")!;
  const base = new URL(root.dataset.source!, location.href);

  let index: Index;
  try {
    index = await fetchJson<Index>(new URL("index.json", base));
  } catch {
    showBody("This build has no benchmark data.");
    return;
  }
  if (!index.commits.length || !index.scopes.length) {
    showBody("No benchmarks have been published yet.");
    return;
  }
  const version = index.generation ?? 0;
  installTooltips(root);

  let scope: Scope = index.scopes.find((s) => s.id === new URLSearchParams(location.search).get("device")) ?? index.scopes[0];
  let current: View = { commits: [], series: {} };
  let scopeLoad = 0;
  const selection = new CommitSelection("measurement", showTiles);

  const scopeSelect = $<HTMLSelectElement>("benchmarks-scope");
  scopeSelect.replaceChildren(
    ...index.scopes.map((s) => el("option", { value: s.id, textContent: `${s.label} · ${s.platform}` })),
  );
  scopeSelect.value = scope.id;
  scopeSelect.addEventListener("change", () => {
    scope = index.scopes.find((s) => s.id === scopeSelect.value)!;
    void loadScope();
  });

  function buildCharts() {
    const { series } = current;
    const hasData = (key: string) => series[key]?.some((v) => v != null) ?? false;
    selection.charts = [];
    const blocks: Node[] = [];
    for (const section of sections) {
      const charts: HTMLElement[] = [];
      for (const item of section.items) {
        for (const [id, c] of Object.entries(index.cases)) {
          if (!item.workloads.includes(c.workload)) continue;
          const metric = (Array.isArray(item.metric) ? item.metric : [item.metric]).find((m) => hasData(`${id}.compose.${m.key}`));
          if (!metric) continue;
          const kinds: Kind[] = hasData(`${id}.classic.${metric.key}`) ? ["compose", "classic"] : ["compose"];
          const spec: ChartSpec = {
            title: section.items.length > 1 ? `${c.title}: ${metric.title.toLowerCase()}` : c.title,
            unit: "ms",
            definition: `${c.description} ${metric.definition}`,
            integer: false,
            format: (value) => (value == null ? "–" : `${formatValue(value)} ms`),
            series: kinds.map((kind) => ({
              key: `${id}.${kind}.${metric.key}`,
              label: kind === "compose" ? "MapLibre Compose" : "Classic SDK",
              definition: kind === "classic" ? definitions.classic : definitions.spread,
            })),
          };
          const bands = kinds.map((kind) =>
            banded.has(metric.key)
              ? ([series[`${id}.${kind}.${metric.key}.min`] ?? [], series[`${id}.${kind}.${metric.key}.max`] ?? []] as Band)
              : undefined,
          );
          const chart = new TrendChart(spec, selection.timeline);
          chart.setData(spec.series.map((s) => series[s.key] ?? []), bands);
          selection.charts.push(chart);
          charts.push(chart.element);
        }
      }
      if (!charts.length) continue;
      blocks.push(
        el("section", { className: "benchmarks-section" }, el("h2", { textContent: section.title }), el("div", { className: "metrics-charts" }, ...charts)),
      );
    }
    $("metrics-charts").replaceChildren(...blocks);
  }

  function showTiles() {
    const { series } = current;
    const { selected } = selection;
    const { index: before, label } = selection.baseline();
    $("metrics-tiles").replaceChildren(
      ...tiles.map((tile) => {
        const column = tile.columns.map((key) => series[key] ?? []).find((c) => c.some((v) => v != null)) ?? [];
        const value = column[selected];
        const previous = column[before];
        const delta = value != null && previous != null && previous !== 0 && selected > 0 ? `${formatPercent((value / previous - 1) * 100)} ${label}` : " ";
        return el(
          "div",
          { className: "metrics-tile" },
          el("div", { className: "metrics-tile-label" }, term(tile.label, tile.definition)),
          el("div", { className: "metrics-tile-value", textContent: formatValue(value) }),
          el("div", { className: "metrics-muted", textContent: column === series[tile.columns[0]] ? tile.note : "ms to settle, median" }),
          el("div", { className: "metrics-tile-delta", textContent: delta }),
        );
      }),
    );
  }

  async function loadScope() {
    const load = ++scopeLoad;
    root.classList.add("metrics-loading");
    setSearchParams({ device: scope === index.scopes[0] ? null : scope.id });
    try {
      const series = await fetchJson<Series>(new URL(`series/${scope.id}.json?v=${version}.${index.commits.length}`, base));
      if (load !== scopeLoad) return;
      current = view(index, series);
      if (!current.commits.length) {
        $("benchmarks-scope-note").textContent = "";
        showBody(`${scope.label} has no measurements.`);
        return;
      }
      // The URL names the selection; a commit this device did not measure opens its latest.
      selection.setCommits(current.commits);
      $("benchmarks-scope-note").textContent = `${current.commits.length} measured commit${current.commits.length === 1 ? "" : "s"}`;
      showBody(null);
      buildCharts();
      selection.show();
      showTiles();
    } catch {
      if (load !== scopeLoad) return;
      $("benchmarks-scope-note").textContent = "";
      showBody("Couldn't load benchmark data.");
    } finally {
      if (load === scopeLoad) root.classList.remove("metrics-loading");
    }
  }

  await loadScope();
}
