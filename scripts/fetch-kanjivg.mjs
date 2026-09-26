#!/usr/bin/env node
// Fetches the KanjiVG SVG for every character in data/curated.json from one
// pinned KanjiVG release tag, and checks each file against data/kanjivg/SHA256SUMS.
//
//   node scripts/fetch-kanjivg.mjs           fetch anything missing, verify everything
//   node scripts/fetch-kanjivg.mjs --verify  offline: verify the committed snapshot only
//   node scripts/fetch-kanjivg.mjs --record  (maintainers) fetch and (re)write SHA256SUMS
//
// The fetched files are committed, so a normal build never touches the network.
// KanjiVG is copyright Ulrich Apel, CC BY-SA 3.0 — see data/LICENSE.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const KANJIVG_TAG = 'r20260714';
const BASE = `https://raw.githubusercontent.com/KanjiVG/kanjivg/${KANJIVG_TAG}`;

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'data', 'kanjivg');
const sumsPath = join(outDir, 'SHA256SUMS');

export const codeOf = (ch) => ch.codePointAt(0).toString(16).padStart(5, '0');
export const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');

export function curatedChars() {
  const curated = JSON.parse(readFileSync(join(root, 'data', 'curated.json'), 'utf8'));
  return curated.groups.flatMap((g) => g.kanji.map((k) => k.c));
}

export function readSums() {
  const sums = new Map();
  if (!existsSync(sumsPath)) return sums;
  for (const line of readFileSync(sumsPath, 'utf8').split('\n')) {
    const m = /^([0-9a-f]{64})\s+(\S+)$/.exec(line.trim());
    if (m) sums.set(m[2], m[1]);
  }
  return sums;
}

async function fetchBytes(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(20000) });
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return Buffer.from(await res.arrayBuffer());
}

async function main() {
  const mode = process.argv.includes('--verify') ? 'verify' : process.argv.includes('--record') ? 'record' : 'fetch';
  mkdirSync(outDir, { recursive: true });
  const sums = readSums();
  const files = [...curatedChars().map((c) => `${codeOf(c)}.svg`), 'COPYING'];
  const problems = [];
  const next = new Map();
  let fetched = 0;

  for (const name of files) {
    const path = join(outDir, name);
    const want = sums.get(name);
    let buf = existsSync(path) ? readFileSync(path) : null;
    const okOnDisk = buf && want && sha256(buf) === want;
    if (mode === 'verify') {
      if (!buf) problems.push(`${name}: missing`);
      else if (!want) problems.push(`${name}: no checksum recorded`);
      else if (!okOnDisk) problems.push(`${name}: checksum mismatch`);
      continue;
    }
    if (!okOnDisk || mode === 'record') {
      const url = name === 'COPYING' ? `${BASE}/COPYING` : `${BASE}/kanji/${name}`;
      buf = await fetchBytes(url);
      fetched++;
      if (want && mode !== 'record' && sha256(buf) !== want) {
        problems.push(`${name}: upstream bytes do not match the pinned checksum`);
        continue;
      }
      writeFileSync(path, buf);
    }
    next.set(name, sha256(buf));
  }

  if (mode !== 'verify') {
    const lines = [...next].sort(([a], [b]) => a.localeCompare(b)).map(([n, h]) => `${h}  ${n}`);
    if (mode === 'record' || lines.length !== sums.size) writeFileSync(sumsPath, lines.join('\n') + '\n');
  }
  if (problems.length) {
    console.error(problems.join('\n'));
    process.exit(1);
  }
  console.log(`KanjiVG ${KANJIVG_TAG}: ${files.length} files ${mode === 'verify' ? 'verified' : `ok (${fetched} fetched)`}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) await main();
