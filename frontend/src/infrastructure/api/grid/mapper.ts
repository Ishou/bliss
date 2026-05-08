// API → domain mapper for the Grid `Puzzle` payload. Two divergences
// drive the work: `Position.column` (wire) vs `Position.col` (domain),
// and one `DefinitionCell` per clue on the wire vs ADR-0005 §3a's
// `[right, down]` stack in the domain. Per ADR-0002 §7 only this layer
// may import the generated types.
import type { components } from './types';
import type { ArrowDirection, Cell, DefinitionCell, DefinitionClue, HorizontalArrow, Puzzle, VerticalArrow } from '@/domain';

type ApiPuzzle = components['schemas']['Puzzle'];
type ApiLetterCell = components['schemas']['LetterCell'];
type ApiDefinitionCell = components['schemas']['DefinitionCell'];
type ApiBlockCell = components['schemas']['BlockCell'];

const positionKey = (row: number, column: number): string => `${row},${column}`;

const toLetter = (cell: ApiLetterCell): Cell => ({
  kind: 'letter',
  position: { row: cell.position.row, col: cell.position.column },
  entry: '',
});

const toBlock = (cell: ApiBlockCell): Cell => ({
  kind: 'block',
  position: { row: cell.position.row, col: cell.position.column },
});

const toClue = (cell: ApiDefinitionCell): DefinitionClue => ({
  text: cell.text, arrow: cell.arrow as ArrowDirection,
});

const isHorizontalArrow = (arrow: ArrowDirection): arrow is HorizontalArrow =>
  arrow === 'right' || arrow === 'down-right';
const isVerticalArrow = (arrow: ArrowDirection): arrow is VerticalArrow =>
  arrow === 'down' || arrow === 'right-down';

const mergeDefinitions = (defs: readonly ApiDefinitionCell[]): DefinitionCell => {
  const first = defs[0];
  const position = { row: first.position.row, col: first.position.column };
  if (defs.length === 1) {
    return { kind: 'definition', position, clues: [toClue(first)] };
  }
  // Two clues at the same cell. When axes mix, render horizontal first then
  // vertical (mots-fléchés convention). When both share an axis (top-row or
  // left-col inner skeleton cells), keep API order — the second clue must not
  // be silently dropped: its word's letter cells would otherwise have no
  // clue context and become unreachable from `useGridNavigation`'s walk.
  const horizontal = defs.find((d) => isHorizontalArrow(d.arrow as ArrowDirection));
  const vertical = defs.find((d) => isVerticalArrow(d.arrow as ArrowDirection));
  if (horizontal && vertical) {
    return { kind: 'definition', position, clues: [toClue(horizontal), toClue(vertical)] };
  }
  return { kind: 'definition', position, clues: [toClue(defs[0]), toClue(defs[1])] };
};

/** Translate a Grid API `Puzzle` document into the frontend's domain `Puzzle`. */
export function apiPuzzleToDomain(api: ApiPuzzle): Puzzle {
  const definitionsByPosition = new Map<string, ApiDefinitionCell[]>();
  const cells: Cell[] = [];
  const indexByKey = new Map<string, number>();

  for (const cell of api.cells) {
    const key = positionKey(cell.position.row, cell.position.column);
    if (cell.kind === 'definition') {
      const bucket = definitionsByPosition.get(key);
      if (bucket) { bucket.push(cell as ApiDefinitionCell); continue; }
      definitionsByPosition.set(key, [cell as ApiDefinitionCell]);
      indexByKey.set(key, cells.length);
      cells.push(null as unknown as Cell);
      continue;
    }
    indexByKey.set(key, cells.length);
    cells.push(cell.kind === 'letter' ? toLetter(cell as ApiLetterCell) : toBlock(cell as ApiBlockCell));
  }
  for (const [key, defs] of definitionsByPosition) {
    const idx = indexByKey.get(key);
    if (idx !== undefined) cells[idx] = mergeDefinitions(defs);
  }
  return {
    id: api.id, title: api.title, language: api.language,
    width: api.width, height: api.height, cells,
  };
}
