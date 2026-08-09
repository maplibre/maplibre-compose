import type { AstroIntegration } from "astro";
import fs from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import path from "node:path";

/**
 * Serves a directory URL under `public/` as that directory's `index.html`,
 * during development only.
 *
 * Static hosts and `astro preview` both resolve `dir/` to `dir/index.html`. The
 * dev server serves `public/` through Vite's static middleware, which resolves
 * exact paths alone, so a directory URL reaches the page router and returns
 * 404. The generated API reference is a tree of such directories: every
 * reference link in the sidebar needs this, the Dart breadcrumbs need it, and
 * so does every Swift DocC route that a reader deep-links or reloads.
 *
 * The base is stripped from `req.url` before the Vite middleware stack runs, so
 * a request arrives here as a path relative to `public/`. The redirect below is
 * the one place that needs the base back, because a browser resolves the
 * `Location` header against the origin.
 */
export function publicDirectoryIndex(base: string): AstroIntegration {
  const root = base.replace(/\/$/, "");

  return {
    name: "public-directory-index",
    hooks: {
      "astro:server:setup": ({ server }) => {
        const publicDir = server.config.publicDir;

        const handle = (
          req: IncomingMessage,
          res: ServerResponse,
          next: (error?: unknown) => void,
        ) => {
          const url = req.url ?? "/";
          const mark = url.search(/[?#]/);
          const pathname = mark === -1 ? url : url.slice(0, mark);
          const suffix = mark === -1 ? "" : url.slice(mark);

          let decoded: string;
          try {
            decoded = decodeURIComponent(pathname);
          } catch {
            next();
            return;
          }

          const target = path.join(publicDir, decoded);
          if (!target.startsWith(publicDir + path.sep)) {
            next();
            return;
          }
          if (!fs.existsSync(path.join(target, "index.html"))) {
            next();
            return;
          }

          // Redirect to the trailing-slash form, as a static host does, so that
          // relative links in the page resolve against the directory itself
          // rather than against its parent.
          if (!decoded.endsWith("/")) {
            res.statusCode = 301;
            res.setHeader("Location", encodeURI(`${root}${decoded}/`) + suffix);
            res.end();
            return;
          }

          req.url = encodeURI(`${decoded}index.html`) + suffix;
          next();
        };

        // Ahead of Vite's static middleware, which is already in the stack and
        // is what serves the rewritten URL.
        server.middlewares.stack.unshift({ route: "", handle });
      },
    },
  };
}
