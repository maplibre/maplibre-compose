import { TrendChart, type Band, type ChartSpec } from "../metrics/chart";
import type { Commit } from "../metrics/model";
import { $, el, fetchJson, setSearchParams, showBody } from "../metrics/page";
import { CommitSelection } from "../metrics/selection";
import { installTooltips } from "../metrics/terms";
import { formatValue, type Index, type Kind, type Scope, type Series } from "./model";

const platformNames: Record<Scope["platform"], string> = { android: "Android", ios: "iOS", desktop: "Desktop", web: "Web" };

const definitions = {
  cpuPerOperation: "Processor time used for each app update, including work in the background.",
  cpuPerSecond: "Processor time used in one second while the map is idle, including work in the background.",
  completion: "Time from starting a change until the map confirms it is ready. Half the updates finish within this time. It may still need to appear on screen.",
  submission: "Time spent preparing and sending a change to the map. Half the updates take this long or less. Preparation can run in the background.",
  runtimeConstruction: "Time spent creating the shared services maps use. This test reopens an empty cache that has already been used once.",
  runtimeReadiness: "Time until the shared map services are ready to use offline data. This test creates no map.",
  close: "Time for close() to return control to the app. Resources may still be releasing in the background.",
  cleanup: "Time until closing has finished and resources are released. The classic iOS test only waits for the map view to be removed.",
  frameInterval: "How long the app waits between opportunities to update its camera or controls. 95% of these waits are this short or shorter.",
  frameIntervalMax: "The longest wait before the app could run its next update.",
  mapDrawInterval: "Time between completed map drawings. 95% of these waits are this short or shorter. Lower is better.",
  mapDrawGap: "The longest wait for the map to finish drawing a new frame during the animation. Lower is better.",
  mapFps: "How many new map frames the renderer draws each second during a camera animation. Higher is faster.",
  startup: "Time from creating a map until it draws its first complete frame. Map data and fonts are already downloaded.",
  classic: "The same test on the same device, using the classic MapLibre SDK instead of MapLibre Compose.",
  spread: "The line is the middle result from repeated runs. The shaded band spans the lowest and highest results.",
  uiFrame: "Time to draw the app around the map, such as buttons and panels. 95% of these frames finish within this time.",
  uiFrameMax: "The longest time taken to draw the app around the map, such as buttons and panels.",
  uiMissed: "Percentage of app frames that finished late. Lower is better.",
  mapReturn: "Time to open a populated map while a panel animates over it. Half the openings finish within this time.",
};

interface Metric {
  key: string;
  title: string;
  definition: string;
  unit?: string;
}

const metrics = {
  cpuPerUpdate: { key: "cpu_ms_per_operation", title: "CPU per update", definition: definitions.cpuPerOperation },
  cpuPerSecond: { key: "cpu_ms_per_second", title: "Idle CPU per second", definition: definitions.cpuPerSecond },
  completion: { key: "completion_p50_ms", title: "Time until an update is ready", definition: definitions.completion },
  submission: { key: "submission_p50_ms", title: "Time to send an update", definition: definitions.submission },
  runtimeConstruction: { key: "submission_p50_ms", title: "Time to create shared map services", definition: definitions.runtimeConstruction },
  runtimeReadiness: { key: "completion_p50_ms", title: "Time until shared map services are ready", definition: definitions.runtimeReadiness },
  close: { key: "close_p50_ms", title: "Time to call close", definition: definitions.close },
  cleanup: { key: "close_completion_p50_ms", title: "Time to finish closing", definition: definitions.cleanup },
  frameInterval: { key: "frame_interval_p95_ms", title: "App update interval, p95", definition: definitions.frameInterval },
  frameIntervalMax: { key: "frame_interval_max_ms", title: "Longest app update interval", definition: definitions.frameIntervalMax },
  mapFps: { key: "map_draw_fps", title: "Map drawing FPS", definition: definitions.mapFps, unit: "FPS" },
  mapDrawInterval: { key: "map_draw_p95_ms", title: "Map drawing interval, p95", definition: definitions.mapDrawInterval },
  mapDrawGap: { key: "map_draw_max_ms", title: "Longest gap between map frames", definition: definitions.mapDrawGap },
  uiMissed: { key: "ui_missed_percent", title: "Late app frames", definition: definitions.uiMissed, unit: "%" },
  startup: { key: "startup_first_frame_ms", title: "Time to first frame", definition: definitions.startup },
  uiFrameP95: { key: "ui_frame_p95_ms", title: "App frame time, p95", definition: definitions.uiFrame },
  uiFrameMax: { key: "ui_frame_max_ms", title: "Longest app frame time", definition: definitions.uiFrameMax },
  appDrawP95: { key: "app_draw_p95_ms", title: "App drawing time, p95", definition: "Time the app spends preparing a drawing of its content. 95% of drawings take this long or less. This does not include the graphics processor or the wait for the screen." },
  appDrawMax: { key: "app_draw_max_ms", title: "Longest app drawing time", definition: "The longest time the app spends preparing a drawing of its content. This does not include the graphics processor or the wait for the screen." },
  returnTime: { key: "completion_p50_ms", title: "Map return time, median", definition: definitions.mapReturn },
} satisfies Record<string, Metric>;

const frameDriven = ["camera", "overlays", "padding", "resize", "recompose"];
const updates = ["source", "source-latency", "layers", "layout", "paint", "sparse-paint", "style", "style-overlay", "overlay-update", "images", "image-cycle", "image-preparation"];
const completedUpdates = ["source-latency", "layers", "layout", "style", "style-overlay", "overlay-update", "images", "image-cycle", "image-preparation"];

/** Every chart has one signal and one unit across its history and SDK comparisons. */
const sections: { title: string; items: { workloads: string[]; metric: Metric }[] }[] = [
  {
    title: "Map drawing",
    items: [
      { workloads: ["animation"], metric: metrics.mapFps },
      { workloads: ["animation"], metric: metrics.mapDrawInterval },
      { workloads: ["animation"], metric: metrics.mapDrawGap },
    ],
  },
  {
    title: "App responsiveness",
    items: [
      { workloads: [...frameDriven, "animation"], metric: metrics.frameInterval },
      { workloads: ["animation"], metric: metrics.frameIntervalMax },
      { workloads: ["map-return", "overlays"], metric: metrics.uiFrameP95 },
      { workloads: ["map-return", "overlays"], metric: metrics.uiFrameMax },
      { workloads: ["map-return", "overlays"], metric: metrics.uiMissed },
      { workloads: ["map-return", "overlays"], metric: metrics.appDrawP95 },
      { workloads: ["map-return", "overlays"], metric: metrics.appDrawMax },
    ],
  },
  { title: "CPU use", items: [{ workloads: [...frameDriven, ...updates], metric: metrics.cpuPerUpdate }] },
  {
    title: "Map updates",
    items: [
      { workloads: updates, metric: metrics.submission },
      { workloads: completedUpdates, metric: metrics.completion },
    ],
  },
  {
    title: "Opening and closing",
    items: [
      { workloads: ["runtime-startup"], metric: metrics.runtimeConstruction },
      { workloads: ["runtime-startup"], metric: metrics.runtimeReadiness },
      { workloads: ["map-return"], metric: metrics.returnTime },
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
  "frame_interval_max_ms",
  "map_draw_fps",
  "map_draw_p95_ms",
  "map_draw_max_ms",
  "ui_missed_percent",
  "app_draw_p95_ms",
  "app_draw_max_ms",
  "ui_frame_p95_ms",
  "ui_frame_max_ms",
  "startup_first_frame_ms",
]);

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
  const selection = new CommitSelection("measurement", () => {});

  const scopeSelect = $<HTMLSelectElement>("benchmarks-scope");
  const platforms = [...new Set(index.scopes.map((s) => s.platform))];
  scopeSelect.replaceChildren(
    ...platforms.map((platform) =>
      el(
        "optgroup",
        { label: platformNames[platform] },
        ...index.scopes.filter((s) => s.platform === platform).map((s) => el("option", { value: s.id, textContent: s.label })),
      ),
    ),
  );
  scopeSelect.value = scope.id;
  scopeSelect.addEventListener("change", () => {
    scope = index.scopes.find((s) => s.id === scopeSelect.value)!;
    void loadScope();
  });

  function buildCharts() {
    const { series } = current;
    selection.charts = [];
    const blocks: Node[] = [];
    for (const section of sections) {
      const charts: HTMLElement[] = [];
      for (const item of section.items) {
        for (const [id, c] of Object.entries({
          "animation-basemap": { title: "Map animation", workload: "animation", classic: true },
          ...index.cases,
        })) {
          if (!item.workloads.includes(c.workload)) continue;
          const metric = item.metric;

          const unit = metric.unit ?? "ms";
          const kinds: Kind[] = c.classic && (scope.platform === "android" || scope.platform === "ios") ? ["compose", "classic"] : ["compose"];
          const spec: ChartSpec = {
            title: section.items.length > 1 ? `${c.title}: ${metric.title.toLowerCase()}` : c.title,
            unit,
            definition: metric.definition,
            integer: false,
            format: (value) => (value == null ? "–" : `${formatValue(value)} ${unit}`),
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
        el("section", { className: "benchmarks-section" },
          el("h2", { textContent: section.title }),
          el("div", { className: "metrics-charts" }, ...charts),
        ),
      );
    }
    $("metrics-charts").replaceChildren(...blocks);
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
        showBody(`${scope.label} has no measurements.`);
        return;
      }
      // The URL names the selection; a commit this device did not measure opens its latest.
      selection.setCommits(current.commits);
      showBody(null);
      buildCharts();
      selection.show();
    } catch {
      if (load !== scopeLoad) return;
      showBody("Couldn't load benchmark data.");
    } finally {
      if (load === scopeLoad) root.classList.remove("metrics-loading");
    }
  }

  await loadScope();
}
