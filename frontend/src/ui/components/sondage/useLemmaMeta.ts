import { useEffect, useRef, useState } from 'react';
import type { LemmaMeta, SurveyClient } from '@/application/survey';

export interface LemmaMetaState {
  readonly data: LemmaMeta | undefined;
  readonly isLoading: boolean;
  readonly isError: boolean;
}

const STALE_MS = 30_000;

interface CacheEntry {
  readonly value: LemmaMeta;
  readonly fetchedAt: number;
}

// Module-scoped cache so re-renders / quick re-mounts (next item with same mot) don't re-fetch.
const cache = new Map<string, CacheEntry>();

export function useLemmaMeta(
  client: SurveyClient | null | undefined,
  mot: string,
): LemmaMetaState {
  const [data, setData] = useState<LemmaMeta | undefined>(() => {
    if (!mot) return undefined;
    const hit = cache.get(mot);
    return hit && Date.now() - hit.fetchedAt < STALE_MS ? hit.value : undefined;
  });
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isError, setIsError] = useState<boolean>(false);
  const reqIdRef = useRef(0);

  useEffect(() => {
    if (!client || !mot) {
      setData(undefined);
      setIsLoading(false);
      setIsError(false);
      return;
    }
    const hit = cache.get(mot);
    if (hit && Date.now() - hit.fetchedAt < STALE_MS) {
      setData(hit.value);
      setIsLoading(false);
      setIsError(false);
      return;
    }
    const reqId = ++reqIdRef.current;
    setIsLoading(true);
    setIsError(false);
    void (async () => {
      try {
        const value = await client.getLemmaMeta(mot);
        if (reqId !== reqIdRef.current) return;
        cache.set(mot, { value, fetchedAt: Date.now() });
        setData(value);
        setIsLoading(false);
      } catch {
        if (reqId !== reqIdRef.current) return;
        setIsError(true);
        setIsLoading(false);
      }
    })();
  }, [client, mot]);

  return { data, isLoading, isError };
}
