import type { Position } from '@/domain';

export const positionKey = (p: Position) => `${p.row},${p.col}`;
