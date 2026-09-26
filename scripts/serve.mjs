#!/usr/bin/env node
// Static server for dist/ (local preview and the browser tests). It applies the rules in
// dist/_headers, so local runs see the same Content-Security-Policy and caching as
// Cloudflare Pages. Binds to 127.0.0.1 only. PORT=0 (the default) picks a free port.
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { dirname, extname, join, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
  '.txt': 'text/plain; charset=utf-8',
};

/** Parses Cloudflare's _headers format into [{pattern: RegExp, headers}] rules. */
export function parseHeaders(text) {
  const rules = [];
  let cur = null;
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    if (!/^\s/.test(line)) {
      const pat = line.trim().replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*');
      cur = { pattern: new RegExp(`^${pat}$`), headers: {} };
      rules.push(cur);
    } else if (cur) {
      const m = /^\s+([^:]+):\s*(.*)$/.exec(line);
      if (m) cur.headers[m[1].toLowerCase()] = m[2];
    }
  }
  return rules;
}

export async function serve(dir, port = 0) {
  const rules = parseHeaders(await readFile(join(dir, '_headers'), 'utf8').catch(() => ''));
  const server = createServer(async (req, res) => {
    try {
      const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
      let file = normalize(join(dir, path));
      if (!file.startsWith(normalize(dir + sep)) && file !== normalize(dir)) {
        res.writeHead(403).end();
        return;
      }
      if ((await stat(file).catch(() => null))?.isDirectory()) file = join(file, 'index.html');
      const body = await readFile(file);
      const headers = {};
      for (const r of rules) if (r.pattern.test(path)) Object.assign(headers, r.headers);
      res.writeHead(200, { ...headers, 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404, { 'content-type': 'text/plain' }).end('not found');
    }
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
  const server = await serve(dir, Number(process.env.PORT ?? 0));
  console.log(`serving dist/ at http://127.0.0.1:${server.address().port}`);
}
