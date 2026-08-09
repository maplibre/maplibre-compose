import type { AstroIntegration } from "astro";
import fs from "node:fs";
import type { IncomingMessage, ServerResponse } from "node:http";
import path from "node:path";

/**
 * Serves a directory URL under `public/` as that directory's `index.html`,
 * during development only.
 *
 * Static hosts and `astro preview` already do this. Vite's static middleware
 * resolves exact paths alone, so the Dokka reference, a tree of directories,
 * 404s in dev without it.
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

          // Vite strips the base before this stack runs, so `decoded` is
          // already relative to `public/`. Only the redirect below needs it back.
          const target = path.join(publicDir, decoded);
          if (!target.startsWith(publicDir + path.sep)) {
            next();
            return;
          }
          if (!fs.existsSync(path.join(target, "index.html"))) {
            next();
            return;
          }

          // As a static host does, so relative links resolve against the
          // directory rather than its parent.
          if (!decoded.endsWith("/")) {
            res.statusCode = 301;
            res.setHeader("Location", encodeURI(`${root}${decoded}/`) + suffix);
            res.end();
            return;
          }

          req.url = encodeURI(`${decoded}index.html`) + suffix;
          next();
        };

        // Ahead of Vite's static middleware, which serves the rewritten URL.
        server.middlewares.stack.unshift({ route: "", handle });
      },
    },
  };
}
