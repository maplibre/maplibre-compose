/** The data written by tools/code-metrics/history.py. */
export interface Index {
  totalCommits: number;
  step: number;
  /** Detekt's reporting threshold per distribution, as used for the `.over` counts. */
  thresholds: Record<string, number>;
  /** Oldest first. */
  commits: Commit[];
  scopes: Scope[];
}

export interface Commit {
  commit: string;
  date: string;
  title: string;
  tags: string[];
}

export interface Scope {
  id: string;
  group: "all" | "library" | "demo";
  module: string | null;
}

/** One column per metric, aligned with [Index.commits]; null where the scope didn't exist. */
export type Series = Record<string, (number | null)[]>;

export interface Ranked {
  name: string;
  value: number;
}

export interface SourceSet {
  module: string;
  name: string;
  isTest: boolean;
  loc: number;
  types: number;
  functions: number;
  cognitiveComplexity: number;
}

export interface Package {
  name: string;
  loc: number;
  types: number;
  dependsOn: string[];
  dependedOnBy: string[];
  instability: number;
}

export interface Snapshot {
  commit: string;
  sourceSets: SourceSet[];
  packages: Package[];
  packageGraph: { cycles: string[][] };
  largest: {
    functionsByCognitiveComplexity: Ranked[];
    functionsByLines: Ranked[];
    filesByLoc: Ranked[];
  };
}

export const repository = "https://github.com/maplibre/maplibre-compose";

export function isRelease(tag: string) {
  return /^v\d+\.\d+\.\d+$/.test(tag);
}

/** A declaration in a ranked list: `path` or `path:line:Name`. */
export function declaration(name: string) {
  const [path, line, qualified] = name.split(":");
  const segments = path.split("/");
  const src = segments.indexOf("src");
  return {
    path,
    line: line ? Number(line) : undefined,
    name: qualified ?? segments.at(-1)!,
    module: segments.slice(0, src).join("/"),
    sourceSet: segments[src + 1],
    file: segments.at(-1)!,
  };
}

export function sourceUrl(commit: string, path: string, line?: number) {
  return `${repository}/blob/${commit}/${path}${line ? `#L${line}` : ""}`;
}

const whole = new Intl.NumberFormat("en");
const compact = new Intl.NumberFormat("en", { notation: "compact" });

export function format(value: number | null | undefined) {
  return value == null ? "–" : whole.format(Math.round(value));
}

export function formatCompact(value: number) {
  return compact.format(value);
}

export function formatDelta(value: number) {
  if (value === 0) return "no change";
  return `${value > 0 ? "+" : "−"}${whole.format(Math.abs(Math.round(value)))}`;
}

export function formatDate(iso: string) {
  return new Date(iso).toLocaleDateString("en", {
    year: "numeric",
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}
