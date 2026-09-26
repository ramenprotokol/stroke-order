// Shared helpers for the browser checks.
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const root = join(dirname(fileURLToPath(import.meta.url)), '..');
export const dist = join(root, 'dist');

/**
 * An expression that, evaluated in the page, returns each reference stroke of [char]
 * as client-space points. It samples KanjiVG's path data with the browser's own SVG
 * geometry (getPointAtLength), independently of the app's Kotlin path parser.
 */
export function referenceStrokes(char) {
  return `(async () => {
    const url = document.querySelector('meta[name=stroke-data]').content;
    const data = await (await fetch(url)).json();
    const k = data.groups.flatMap((g) => g.kanji).find((k) => k.c === ${JSON.stringify(char)});
    const sheet = document.getElementById('sheet');
    const r = sheet.getBoundingClientRect();
    const box = 0.86 * r.width, left = r.left + 0.07 * r.width, top = r.top + 0.07 * r.width;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    document.body.appendChild(svg);
    const out = k.s.map((s) => {
      const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      p.setAttribute('d', s.d);
      svg.appendChild(p);
      const len = p.getTotalLength();
      const n = Math.max(12, Math.round(len / 2.5));
      const pts = [];
      for (let i = 0; i <= n; i++) {
        const q = p.getPointAtLength((len * i) / n);
        pts.push([left + (q.x / 109) * box, top + (q.y / 109) * box]);
      }
      return pts;
    });
    svg.remove();
    return out;
  })()`;
}

export const centreSheet = `document.getElementById('sheet').scrollIntoView({ block: 'center' })`;
