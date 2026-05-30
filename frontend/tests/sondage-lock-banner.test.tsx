import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { Campaign } from '@/application/survey';
import { LockBanner } from '@/ui/components/sondage/LockBanner';

const closedCampaign: Campaign = {
  campaignId: '00000000-0000-0000-0000-000000000007',
  batchLabel: 'round-7',
  openedAt: '2026-05-30T10:00:00Z',
  closedAt: '2026-05-30T12:00:00Z',
};

describe('LockBanner', () => {
  it('renders the closed-state copy', () => {
    render(<LockBanner campaign={closedCampaign} />);
    expect(screen.getByRole('status')).toHaveTextContent(/campagne en pause/i);
  });

  it('uses polite aria-live so screen readers do not interrupt', () => {
    render(<LockBanner campaign={closedCampaign} />);
    expect(screen.getByRole('status')).toHaveAttribute('aria-live', 'polite');
  });

  it('exposes the batch label as a data attribute for telemetry', () => {
    render(<LockBanner campaign={closedCampaign} />);
    expect(screen.getByTestId('sondage-lock-banner')).toHaveAttribute(
      'data-batch-label',
      'round-7',
    );
  });
});
