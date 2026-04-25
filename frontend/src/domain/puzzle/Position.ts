// 0-indexed grid coordinate. Row grows downward, column grows rightward —
// the same orientation a player sees on screen, so adapters and renderers
// can share a single mental model without translating axes.
export interface Position {
  readonly row: number;
  readonly col: number;
}
