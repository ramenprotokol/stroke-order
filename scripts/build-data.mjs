// Builds the single stroke-data file the page loads: the curated 80 characters, their
// meanings and readings (data/curated.json), and each stroke's path, type and number
// position extracted from the pinned KanjiVG snapshot (data/kanjivg/*.svg).
//
// The output is an adaptation of KanjiVG, so it carries KanjiVG's attribution and the
// CC BY-SA 3.0 licence inside the file itself.
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { KANJIVG_TAG, codeOf, readSums, sha256 } from './fetch-kanjivg.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

export const ATTRIBUTION =
  'Stroke data from KanjiVG (https://kanjivg.tagaini.net), copyright Ulrich Apel, ' +
  `release ${KANJIVG_TAG}, licensed under CC BY-SA 3.0. Adapted for stroke-order: ` +
  'stroke paths, stroke types and stroke-number positions were extracted for 80 characters ' +
  'and combined with English meanings and readings written for this app.';
export const LICENSE = 'CC BY-SA 3.0 — https://creativecommons.org/licenses/by-sa/3.0/';

/** Parses one KanjiVG SVG into ordered strokes. Throws on anything unexpected. */
export function parseKanjiVg(svg, ch) {
  const code = codeOf(ch);
  const strokes = [];
  for (const m of svg.matchAll(/<path\s+([^>]*?)\/?>/g)) {
    const attrs = Object.fromEntries([...m[1].matchAll(/([\w:]+)="([^"]*)"/g)].map((a) => [a[1], a[2]]));
    const id = /^kvg:([0-9a-f]{5})-s(\d+)$/.exec(attrs.id ?? '');
    if (!id || id[1] !== code) throw new Error(`${ch}: unexpected path id ${attrs.id}`);
    if (!attrs.d) throw new Error(`${ch}: stroke ${id[2]} has no path data`);
    strokes.push({ n: Number(id[2]), d: attrs.d, t: attrs['kvg:type'] ?? '' });
  }
  strokes.sort((a, b) => a.n - b.n);
  strokes.forEach((s, i) => {
    if (s.n !== i + 1) throw new Error(`${ch}: stroke numbers are not 1..${strokes.length}`);
  });
  const labels = new Map();
  for (const m of svg.matchAll(/<text transform="matrix\(1 0 0 1 ([\d.]+) ([\d.]+)\)">(\d+)<\/text>/g)) {
    labels.set(Number(m[3]), [Number(m[1]), Number(m[2])]);
  }
  return strokes.map((s) => ({ d: s.d, t: s.t, ...(labels.has(s.n) ? { n: labels.get(s.n) } : {}) }));
}

export function buildData() {
  const curated = JSON.parse(readFileSync(join(root, 'data', 'curated.json'), 'utf8'));
  const sums = readSums();
  const seen = new Set();
  const groups = curated.groups.map((g) => ({
    id: g.id,
    title: g.title,
    jp: g.jp,
    kanji: g.kanji.map((k) => {
      if (seen.has(k.c)) throw new Error(`${k.c} appears twice in curated.json`);
      seen.add(k.c);
      const file = `${codeOf(k.c)}.svg`;
      const buf = readFileSync(join(root, 'data', 'kanjivg', file));
      if (sums.get(file) !== sha256(buf)) throw new Error(`${file} does not match data/kanjivg/SHA256SUMS`);
      return { c: k.c, m: k.m, on: k.on, kun: k.kun, s: parseKanjiVg(buf.toString('utf8'), k.c) };
    }),
  }));
  const data = {
    format: 1,
    attribution: ATTRIBUTION,
    license: LICENSE,
    source: { name: 'KanjiVG', url: 'https://kanjivg.tagaini.net', release: KANJIVG_TAG },
    groups,
  };
  return { data, json: JSON.stringify(data) };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const { data, json } = buildData();
  const count = data.groups.reduce((n, g) => n + g.kanji.length, 0);
  const strokes = data.groups.reduce((n, g) => n + g.kanji.reduce((m, k) => m + k.s.length, 0), 0);
  console.log(`${count} characters, ${strokes} strokes, ${json.length} bytes`);
}
