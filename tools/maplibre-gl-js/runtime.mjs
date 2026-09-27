export * from "./maplibre-gl.mjs";
import workerSource from "./worker-source.mjs";

let workerUrl;
export function getDefaultWorkerUrl() {
  workerUrl ??= URL.createObjectURL(new Blob([workerSource], { type: "text/javascript" }));
  return workerUrl;
}
