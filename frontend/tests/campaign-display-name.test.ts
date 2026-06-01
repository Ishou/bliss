import { describe, it, expect } from 'vitest';
import type { Campaign } from '../src/application/survey/types';
import { campaignDisplayName } from '../src/application/survey/campaignName';

function makeCampaign(batchLabel: string, openedAt: string): Campaign {
  return { campaignId: 'c-1', batchLabel, openedAt, closedAt: null };
}

describe('campaignDisplayName', () => {
  it('formats round number and date', () => {
    expect(campaignDisplayName(makeCampaign('round-9', '2026-05-30T08:24:34Z'))).toBe(
      'Moineau 9 — 30/05/2026',
    );
  });

  it('handles double-digit rounds and zero-padded dates', () => {
    expect(campaignDisplayName(makeCampaign('round-12', '2026-01-05T00:00:00Z'))).toBe(
      'Moineau 12 — 05/01/2026',
    );
  });

  it('falls back to the raw batchLabel when it does not match the round pattern', () => {
    expect(campaignDisplayName(makeCampaign('gold_v1', '2026-05-30T08:24:34Z'))).toBe(
      'Moineau gold_v1 — 30/05/2026',
    );
  });
});
