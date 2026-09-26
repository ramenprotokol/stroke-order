// dist/ is complete, deployable to Cloudflare Pages, and caches only hashed files.
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { dist } from './helpers.mjs';

function walk(dir) {
  return readdirSync(dir).flatMap((f) => (statSync(join(dir, f)).isDirectory() ? walk(join(dir, f)) : [join(dir, f)]));
}

test('dist/ exists (run npm run build first)', () => {
  assert.ok(existsSync(join(dist, 'index.html')), 'dist/index.html');
});

test('every asset index.html references exists and is content-hashed', () => {
  const html = readFileSync(join(dist, 'index.html'), 'utf8');
  const refs = [...html.matchAll(/(?:href|src|content)="(assets\/[^"]+)"/g)].map((m) => m[1]);
  assert.ok(refs.length >= 5, `found ${refs.length} asset references`);
  for (const r of refs) {
    assert.ok(existsSync(join(dist, r)), `${r} exists`);
    assert.match(r, /\.[0-9a-f]{10}\.\w+$/, `${r} is hashed`);
  }
  const css = readFileSync(join(dist, refs.find((r) => r.endsWith('.css'))), 'utf8');
  for (const m of css.matchAll(/url\("([^"]+)"\)/g)) assert.ok(existsSync(join(dist, 'assets', m[1])), `font ${m[1]}`);
  assert.doesNotMatch(html + css, /\{\{\w+\}\}/, 'no unreplaced placeholders');
});

test('long caching applies only to hashed assets; CSP is set', () => {
  const headers = readFileSync(join(dist, '_headers'), 'utf8');
  const blocks = headers.split(/\n(?=\S)/);
  for (const b of blocks) {
    if (/max-age=31536000/.test(b)) assert.match(b.split('\n')[0], /^\/assets\/\*$/, 'immutable caching only on /assets/*');
  }
  assert.match(headers, /Content-Security-Policy: default-src 'self'; script-src 'self' 'sha256-/);
  assert.match(headers, /frame-ancestors 'none'/);
});

test('fits Cloudflare Pages limits (20,000 files, 25 MiB per file)', () => {
  const files = walk(dist);
  assert.ok(files.length < 20000);
  for (const f of files) assert.ok(statSync(f).size < 25 * 1024 * 1024, f);
});
