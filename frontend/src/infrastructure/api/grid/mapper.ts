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
  ...(cell.letter == null ? {} : { answer: cell.letter }),
  entry: '',
});

const toBlock = (cell: ApiBlockCell): Cell => ({
  kind: 'block',
  position: { row: cell.position.row, col: cell.position.column },
});

const toClue = (cell: ApiDefinitionCell): DefinitionClue => ({
  text: cell.text, arrow: cell.arrow as ArrowDirection,
});

const isHorizontalArrow = (arrow: string): boolean => arrow === 'right';
const isVerticalArrow = (arrow: string): boolean => arrow === 'down';

const mergeDefinitions = (defs: readonly ApiDefinitionCell[]): DefinitionCell => {
  const horizontal = defs.find((d) => isHorizontalArrow(d.arrow));
  const vertical = defs.find((d) => isVerticalArrow(d.arrow));
  const first = defs[0];
  const position = { row: first.position.row, col: first.position.column };
  if (horizontal && vertical) {
    return {
      kind: 'definition', position,
      clues: [
        toClue(horizontal) as DefinitionClue & { arrow: HorizontalArrow },
        toClue(vertical) as DefinitionClue & { arrow: VerticalArrow },
      ],
    };
  }
  return { kind: 'definition', position, clues: [toClue(first)] };
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
