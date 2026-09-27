/** The page structure and DOM helpers the dashboards share. */
import { newTab, repository } from "./model";

export const $ = <T extends HTMLElement = HTMLElement>(id: string) => document.getElementById(id) as T;

export function el<K extends keyof HTMLElementTagNameMap>(
  tag: K,
  props: Partial<HTMLElementTagNameMap[K]> = {},
  ...children: (Node | string)[]
) {
  const element = Object.assign(document.createElement(tag), props);
  element.append(...children);
  return element;
}

export function commitLink(commit: string) {
  return el("a", { ...newTab, className: "metrics-sha", href: `${repository}/commit/${commit}`, textContent: commit.slice(0, 7) });
}

export function releaseLink(tag: string) {
  return el("a", { ...newTab, href: `${repository}/releases/tag/${tag}`, textContent: tag });
}

export async function fetchJson<T>(url: URL): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  return response.json();
}

/** Records [values] in the page's query string; a null value removes its parameter. */
export function setSearchParams(values: Record<string, string | null>) {
  const url = new URL(location.href);
  for (const [key, value] of Object.entries(values)) {
    if (value) url.searchParams.set(key, value);
    else url.searchParams.delete(key);
  }
  history.replaceState(null, "", url);
}

/** Shows the page body and the commit selection, or [status] in their place. */
export function showBody(status: string | null) {
  $("metrics-body").hidden = status != null;
  $("metrics-commit").hidden = status != null;
  $("metrics-status").hidden = status == null;
  if (status != null) $("metrics-status").textContent = status;
}
