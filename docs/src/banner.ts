import { defineRouteMiddleware } from "@astrojs/starlight/route-data";

/**
 * Shows a site-wide banner when the build sets DOCS_BANNER, which CI uses to
 * mark Cloudflare Pages previews of main or of a pull request. The value may
 * contain HTML. Release builds set no banner.
 */
export const onRequest = defineRouteMiddleware(({ locals }) => {
  const content = process.env.DOCS_BANNER;
  if (content) {
    locals.starlightRoute.entry.data.banner ??= { content };
  }
});
