/** Material Symbols, outlined, 20px: https://fonts.google.com/icons */
export const icons = {
  cycle:
    "M331-126q-107-45-171-141T96-479q0-32 5-62.5t15-60.5l-71 41-36-63 187-108 108 188-62 36-51-88q-11 28-17 57.5t-6 60.5q0 97 54.5 175.5T368-189l-37 63Zm293-474v-72h102q-43-55-107-87.5T480-792q-56 0-105 19t-90 51l-37-63q48-37 107-58t125-21q87 0 160.5 35.5T768-733v-83h72v216H624ZM590 0 403-108l108-187 63 36-51 88q115-17 192-104.5T792-477q0-13-1-25.5t-3-25.5h73q2 12 2.5 24.5t.5 25.5q0 136-87.5 242T555-103l71 41-36 62Z",
  openInNew:
    "M216-144q-29.7 0-50.85-21.15Q144-186.3 144-216v-528q0-29.7 21.15-50.85Q186.3-816 216-816h264v72H216v528h528v-264h72v264q0 29.7-21.15 50.85Q773.7-144 744-144H216Zm171-192-51-51 357-357H576v-72h240v240h-72v-117L387-336Z",
  skipPrevious: "M240-240v-480h72v480h-72Zm480-24L384-480l336-216v432Zm-72-216Zm0 84v-168l-131 84 131 84Z",
  chevronLeft: "M576-240 336-480l240-240 51 51-189 189 189 189-51 51Z",
  chevronRight: "M522-480 333-669l51-51 240 240-240 240-51-51 189-189Z",
  skipNext: "M648-240v-480h72v480h-72Zm-408-24v-432l336 216-336 216Zm72-216Zm0 84 131-84-131-84v168Z",
};

export function icon(name: keyof typeof icons) {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("viewBox", "0 -960 960 960");
  svg.setAttribute("aria-hidden", "true");
  svg.classList.add("metrics-icon");
  const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
  path.setAttribute("d", icons[name]);
  svg.append(path);
  return svg;
}
