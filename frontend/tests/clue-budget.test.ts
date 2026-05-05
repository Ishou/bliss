/**
 * Corpus contract: every row in the runtime words-fr.csv has a `compact`
 * column with a defined value, and every clue text is non-empty. The
 * pixel-fit check is enforced offline by `scripts/eval/clue_metrics.py`
 * during corpus build; this test catches:
 *   - missing/blank `compact` column (would cause grid generator to
 *     misbehave on stacked cells)
 *   - blank clues (the API requires non-empty per CsvWordRepository.kt)
 *   - reasonable distribution of compact-eligible rows (sanity floor)
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const REPO = resolve(__dirname, '..', '..');
const WORDLIST = resolve(REPO, 'grid/api/src/main/resources/words/words-fr.csv');

interface Row {
  word: string;
  clue: string;
  compact: string;
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
  const compactIdx = header.indexOf('compact');
  expect(wordIdx).toBeGreaterThanOrEqual(0);
  expect(clueIdx).toBeGreaterThanOrEqual(0);
  expect(compactIdx).toBeGreaterThanOrEqual(0);
  const rows: Row[] = [];
  for (let i = 1; i < lines.length; i++) {
    const fields = splitCsvLine(lines[i]);
    rows.push({
      word: fields[wordIdx],
      clue: fields[clueIdx],
      compact: fields[compactIdx] ?? '',
    });
  }
  return rows;
}

describe('words-fr.csv corpus contract', () => {
  const rows = parseCSV(readFileSync(WORDLIST, 'utf8'));

  it('every row has a non-empty clue', () => {
    const blank = rows.filter((r) => !r.clue || !r.clue.trim());
    expect(blank).toHaveLength(0);
  });

  it('every row has a defined compact value (true|false)', () => {
    const undefinedCompact = rows.filter(
      (r) => r.compact !== 'true' && r.compact !== 'false',
    );
    if (undefinedCompact.length > 0) {
      // Aid debugging: surface the first few offenders.
      console.error('rows with bad compact:', undefinedCompact.slice(0, 5));
    }
    expect(undefinedCompact).toHaveLength(0);
  });

  it('at least 30% of rows are compact-eligible (stacked-fit)', () => {
    const compactCount = rows.filter((r) => r.compact === 'true').length;
    const ratio = compactCount / rows.length;
    expect(ratio).toBeGreaterThan(0.3);
  });
});
