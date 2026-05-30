import { useCallback, useEffect, useState } from 'react';
import type { Campaign, SurveyClient } from '@/application/survey';

export type CampaignStatus =
  | { readonly kind: 'loading' }
  | { readonly kind: 'open'; readonly campaign: Campaign }
  | { readonly kind: 'closed'; readonly campaign: Campaign };

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
    } catch {
      // keep prior status on transient failure so a network blip doesn't flash the LockBanner
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
