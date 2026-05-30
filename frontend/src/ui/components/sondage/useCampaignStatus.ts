import { useCallback, useEffect, useState } from 'react';
import type { Campaign, SurveyClient } from '@/application/survey';

export type CampaignStatus =
  | { readonly kind: 'loading' }
  | { readonly kind: 'open'; readonly campaign: Campaign }
  | { readonly kind: 'closed'; readonly campaign: Campaign }
  | { readonly kind: 'unavailable' };

export interface CampaignStatusApi {
  readonly status: CampaignStatus;
  readonly refresh: () => void;
}

export function useCampaignStatus(client: SurveyClient | null | undefined): CampaignStatusApi {
  const [status, setStatus] = useState<CampaignStatus>({ kind: 'loading' });

  const fetchStatus = useCallback(async () => {
    if (!client) return;
    try {
      const c = await client.getCurrentCampaign();
      setStatus({
        kind: c.closedAt === null ? 'open' : 'closed',
        campaign: c,
      });
    } catch (cause) {
      // 503 → no campaign has ever been opened. Other errors stay transient.
      const name = (cause as Error | undefined)?.name ?? '';
      if (name === 'NoCampaignError') {
        setStatus({ kind: 'unavailable' });
        return;
      }
      // transient network/server blip — keep prior status so the banner doesn't flash
    }
  }, [client]);

  useEffect(() => {
    void fetchStatus();
  }, [fetchStatus]);

  useEffect(() => {
    function onVisibility() {
      if (document.visibilityState === 'visible') void fetchStatus();
    }
    document.addEventListener('visibilitychange', onVisibility);
    return () => document.removeEventListener('visibilitychange', onVisibility);
  }, [fetchStatus]);

  const refresh = useCallback(() => {
    void fetchStatus();
  }, [fetchStatus]);

  return { status, refresh };
}
