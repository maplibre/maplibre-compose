// @ts-check

import { publicDirectoryIndex } from "./src/integrations/public-directory-index";
import { remarkVersions } from "./src/plugins/remark-versions.mjs";
import starlight from "@astrojs/starlight";
import { defineConfig } from "astro/config";
import starlightCopyButton from "starlight-copy-button";
import starlightLinksValidator from "starlight-links-validator";

const base = "/maplibre-compose";

// https://astro.build/config
export default defineConfig({
  site: "https://maplibre.org",
  base,
  markdown: { remarkPlugins: [remarkVersions] },
  integrations: [
    publicDirectoryIndex(base),
    starlight({
      title: "MapLibre Compose",
      logo: {
        light: "./src/assets/maplibre-logo-square-for-light-bg.svg",
        dark: "./src/assets/maplibre-logo-square-for-dark-bg.svg",
      },
      editLink: {
        baseUrl: "https://github.com/maplibre/maplibre-compose/edit/main/docs/",
      },
      routeMiddleware: "./src/banner.ts",
      customCss: ["./src/styles/custom.css"],
      plugins: [
        starlightCopyButton(),
        // Generated static sites own these paths outside Astro's page tree.
        starlightLinksValidator({ exclude: [`${base}/api/**`, `${base}/demo/**`] }),
      ],
      social: [
        {
          icon: "github",
          label: "GitHub",
          href: "https://github.com/maplibre/maplibre-compose",
        },
      ],
      sidebar: [
        { label: "Overview", link: "/" },
        {
          label: "Live demo",
          link: "/demo/",
          attrs: { target: "_blank", rel: "noopener noreferrer" },
        },
        { label: "Getting started", slug: "getting-started" },
        {
          label: "Guides",
          items: [
            { label: "Style the map", slug: "styling" },
            { label: "Control the camera", slug: "camera" },
            { label: "Handle gestures and clicks", slug: "interaction" },
            { label: "Add data to the map", slug: "layers" },
            { label: "Add images and icons", slug: "images" },
            { label: "Overlay Compose UI", slug: "controls" },
            { label: "Show the user's location", slug: "location" },
            { label: "Download maps for offline use", slug: "offline" },
          ],
        },
        {
          label: "Concepts",
          items: [
            { label: "How the map composes", slug: "composition" },
            { label: "Expressions in Kotlin", slug: "expressions" },
          ],
        },
        {
          label: "API reference",
          link: "/api/",
          attrs: { target: "_blank", rel: "noopener noreferrer" },
        },
      ],
    }),
  ],
});
