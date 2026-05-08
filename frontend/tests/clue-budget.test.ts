/**
 * Corpus contract: every non-empty clue in the runtime words-fr.csv
 * fits within the `MAX_CLUE_CHARS=25` hard cap introduced in PR #208
 * (which retired the per-row `compact` flag — the cap alone now
 * guarantees both single-cell and stacked-cell layouts fit at the
 * legibility floor). The pixel-fit check is enforced offline by
 * `scripts/eval/clue_metrics.py` during corpus build.
 *
 * Empty clues are allowed: PR #203 adopted the empty-clue convention,
 * so a row may carry a word with no curated clue (the runtime path
 * skips it; the LoRA pipeline fills it on the next iter). Word and
 * header columns must still be present.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const REPO = resolve(__dirname, '..', '..');
const WORDLIST = resolve(REPO, 'grid/api/src/main/resources/words/words-fr.csv');

// Mirrors `MAX_CLUE_CHARS` in the grid bounded context. Hard-coded here
// rather than imported because the Kotlin source is in a different
// language; if either side changes, this number must move with it.
const MAX_CLUE_CHARS = 25;

interface Row {
  word: string;
  clue: string;
}

/**
 * Minimal RFC-4180 line splitter. Handles double-quoted fields containing
 * commas (e.g. `"Possèdent, tenir, détenir"`) — the corpus uses these for
 * verb-form clues with internal commas. Doesn't handle escaped quotes
 * inside fields ("") because the corpus never produces them, and adding
 * a real CSV parser would pull in a dependency we don't want.
 */
function splitCsvLine(line: string): string[] {
  const fields: string[] = [];
  let current = '';
  let inQuotes = false;
  for (let i = 0; i < line.length; i++) {
    const ch = line[i];
    if (ch === '"') {
      inQuotes = !inQuotes;
    } else if (ch === ',' && !inQuotes) {
      fields.push(current);
      current = '';
    } else {
      current += ch;
    }
  }
  fields.push(current);
  return fields;
}

function parseCSV(text: string): Row[] {
  const lines = text.trimEnd().split('\n');
  const header = splitCsvLine(lines[0]);
  const wordIdx = header.indexOf('word');
  const clueIdx = header.indexOf('clue');
  expect(wordIdx).toBeGreaterThanOrEqual(0);
  expect(clueIdx).toBeGreaterThanOrEqual(0);
  const rows: Row[] = [];
  for (let i = 1; i < lines.length; i++) {
    const fields = splitCsvLine(lines[i]);
    rows.push({
      word: fields[wordIdx],
      clue: fields[clueIdx],
    });
  }
  return rows;
}

describe('words-fr.csv corpus contract', () => {
  const rows = parseCSV(readFileSync(WORDLIST, 'utf8'));

  it('every row has a word', () => {
    const blank = rows.filter((r) => !r.word || !r.word.trim());
    expect(blank).toHaveLength(0);
  });

  it(`every non-empty clue fits within MAX_CLUE_CHARS=${MAX_CLUE_CHARS}`, () => {
    const overrun = rows.filter(
      (r) => r.clue && r.clue.length > MAX_CLUE_CHARS,
    );
    if (overrun.length > 0) {
      console.error(
        'clues over the cap:',
        overrun.slice(0, 5).map((r) => `${r.word}: "${r.clue}" (${r.clue.length})`),
      );
    }
    expect(overrun).toHaveLength(0);
  });
});
