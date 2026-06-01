import type { Campaign } from './types';

export function campaignDisplayName(c: Campaign): string {
  const m = /round-(\d+)/.exec(c.batchLabel);
  const version = m ? m[1] : c.batchLabel;
  const [y, mo, d] = c.openedAt.slice(0, 10).split('-');
  return `Moineau ${version} — ${d}/${mo}/${y}`;
}
