/** The data written by benchmarks/publish.py. */
import type { Commit } from "../metrics/model";

export interface Index {
  generation?: number;
  /** Measured commits, oldest first. Each scope has values for a subset of them. */
  commits: Commit[];
  scopes: Scope[];
  /** The tracked cases, in page order. */
  cases: Record<string, Case>;
}

/** One device the maintainer measures on. */
export interface Scope {
  id: string;
  label: string;
  platform: "android" | "ios" | "desktop" | "web";
}

export interface Case {
  title: string;
  description: string;
  workload: string;
  scene: string;
  implementation: string;
  /** Whether the classic SDK of the platform runs the same workload for comparison. */
  classic: boolean;
}

/** `<case>.<compose|classic>.<metric>`, aligned with [Index.commits]; null where unmeasured. */
export type Series = Record<string, (number | null)[]>;

export type Kind = "compose" | "classic";

/** Two decimals for small values, whole numbers for large ones. */
export function formatValue(value: number | null | undefined) {
  if (value == null) return "–";
  const magnitude = Math.abs(value);
  const digits = magnitude >= 100 ? 0 : magnitude >= 10 ? 1 : 2;
  return value.toLocaleString("en", { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function formatPercent(value: number) {
  const rounded = Math.round(value);
  if (rounded === 0) return "no change";
  return `${rounded > 0 ? "+" : "−"}${Math.abs(rounded)}%`;
}
