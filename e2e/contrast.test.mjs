// WCAG AA contrast (4.5:1 for body and caption text) for both papers, computed from the
// colour tokens in web/style.css and the canvas colours in Theme.kt.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { root } from './helpers.mjs';

const css = readFileSync(join(root, 'web', 'style.css'), 'utf8');
const themeKt = readFileSync(join(root, 'src', 'jsMain', 'kotlin', 'strokeorder', 'ui', 'Theme.kt'), 'utf8');

function tokens(selector) {
  const start = css.indexOf(selector);
  assert.ok(start >= 0, `selector ${selector}`);
  const body = css.slice(css.indexOf('{', start) + 1, css.indexOf('}', start));
  return Object.fromEntries([...body.matchAll(/--([\w-]+):\s*(#[0-9a-f]{6})/gi)].map((m) => [m[1], m[2]]));
}

/** Canvas colours from a Theme(...) block in Theme.kt. */
function canvas(name) {
  const block = themeKt.slice(themeKt.indexOf(`name = "${name}"`));
  const pick = (k) => new RegExp(`${k} = "(#[0-9a-f]{6})"`, 'i').exec(block)[1];
  return { paper: pick('paper'), demo: pick('demo') };
}

function lum(hex) {
  const c = [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4));
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
}
const ratio = (a, b) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x + 0.05) / (y + 0.05);
};

const themes = {
  washi: { ...tokens(':root {'), canvas: canvas('washi') },
  'indigo (system dark)': { ...tokens(':root:not([data-theme="washi"])'), canvas: canvas('indigo') },
  'indigo (chosen)': { ...tokens(':root[data-theme="indigo"]'), canvas: canvas('indigo') },
};

for (const [name, t] of Object.entries(themes)) {
  test(`${name}: text colours reach WCAG AA`, () => {
    const pairs = [
      ['text on desk', t.text, t.desk, 4.5],
      ['text on paper', t.text, t.paper, 4.5],
      ['muted on desk', t.muted, t.desk, 4.5],
      ['muted on paper (placeholder)', t.muted, t.paper, 4.5],
      ['vermilion links on desk', t.shu, t.desk, 4.5],
      ['primary button', t['primary-fg'], t['primary-bg'], 4.5],
      ['written stroke number', t.desk, t.text, 4.5],
      ['sheet caption on the paper', t.caption, t.canvas.paper, 4.5],
      ['Show me numbers (large text) on the paper', t.canvas.demo, t.canvas.paper, 3.0],
    ];
    for (const [label, fg, bg, min] of pairs) {
      assert.ok(fg && bg, `${label}: colours found`);
      const r = ratio(fg, bg);
      assert.ok(r >= min, `${label}: ${fg} on ${bg} is ${r.toFixed(2)}:1, needs ${min}:1`);
    }
  });
}
