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
      customCss: ["./src/styles/custom.css"],
      plugins: [
        starlightCopyButton(),
        // Dokka owns /api/, and it is generated rather than checked in.
        starlightLinksValidator({ exclude: [`${base}/api/**`] }),
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
        { label: "Getting started", slug: "getting-started" },
        { label: "Styling", slug: "styling" },
        { label: "Interaction", slug: "interaction" },
        { label: "Layers", slug: "layers" },
        { label: "Location", slug: "location" },
        { label: "Map controls", slug: "controls" },
        { label: "Material 3", slug: "material3" },
        {
          label: "API reference",
          link: "/api/",
          attrs: { target: "_blank", rel: "noopener noreferrer" },
        },
        { label: "Roadmap", slug: "roadmap" },
      ],
    }),
  ],
});
