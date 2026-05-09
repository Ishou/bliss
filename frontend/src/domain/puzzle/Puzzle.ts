import type { Cell } from './Cell';

// A single mots fléchés puzzle. `width` and `height` define the grid
// extent; `cells` is a sparse listing — positions absent from `cells` are
// implicitly empty (renderable as blanks). `language` is a BCP-47 tag and
// drives the `lang` attribute applied to the rendered grid for assistive
// tech. `hintsAllowed` is the per-puzzle hint budget the player may spend
// against `POST /v1/puzzles/{id}/hints`; the server is authoritative on
// the running counter, this field only seeds the UI.
export type Difficulty = 'facile' | 'moyen' | 'difficile';

export interface Puzzle {
  readonly id: string;
  readonly title: string;
  readonly language: string;
  readonly width: number;
  readonly height: number;
  readonly hintsAllowed: number;
  readonly cells: readonly Cell[];
  readonly difficulty?: Difficulty | null;
  readonly gridNumber?: number | null;
}
