import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Campaign, SurveyClient } from '@/application/survey';
import { useCampaignStatus } from '@/ui/components/sondage/useCampaignStatus';

function makeClient(campaign: Campaign): SurveyClient {
  return {
    getCurrentCampaign: vi.fn().mockResolvedValue(campaign),
  } as unknown as SurveyClient;
}

const openCampaign: Campaign = {
  campaignId: '00000000-0000-0000-0000-000000000007',
  batchLabel: 'round-7',
  openedAt: '2026-05-30T10:00:00Z',
  closedAt: null,
};

const closedCampaign: Campaign = {
  ...openCampaign,
  closedAt: '2026-05-30T12:00:00Z',
};

describe('useCampaignStatus', () => {
  beforeEach(() => {
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('starts in loading, then resolves to open', async () => {
    const client = makeClient(openCampaign);
    const { result } = renderHook(() => useCampaignStatus(client));
    expect(result.current.status.kind).toBe('loading');
    await waitFor(() => expect(result.current.status.kind).toBe('open'));
    expect(client.getCurrentCampaign).toHaveBeenCalledTimes(1);
  });

  it('resolves to closed when closedAt is non-null', async () => {
    const client = makeClient(closedCampaign);
    const { result } = renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(result.current.status.kind).toBe('closed'));
  });

  it('refetches on visibilitychange when tab becomes visible', async () => {
    const client = makeClient(openCampaign);
    renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(1));
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(2));
  });

  it('refresh() triggers an additional fetch', async () => {
    const client = makeClient(openCampaign);
    const { result } = renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(1));
    await act(async () => {
      result.current.refresh();
    });
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(2));
  });

  it('keeps previous status on transient fetch failure', async () => {
    const client = {
      getCurrentCampaign: vi
        .fn()
        .mockResolvedValueOnce(openCampaign)
        .mockRejectedValueOnce(new Error('boom')),
    } as unknown as SurveyClient;
    const { result } = renderHook(() => useCampaignStatus(client));
    await waitFor(() => expect(result.current.status.kind).toBe('open'));
    await act(async () => {
      result.current.refresh();
    });
    await waitFor(() => expect(client.getCurrentCampaign).toHaveBeenCalledTimes(2));
    expect(result.current.status.kind).toBe('open');
  });
});
