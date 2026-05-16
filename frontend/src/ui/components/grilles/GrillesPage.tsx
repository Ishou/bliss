import { useCallback, useEffect, useRef, useState } from 'react';
import { css } from 'styled-system/css';
import type {
  DailySummariesPage,
  DailySummary,
  PuzzleRepository,
} from '@/application';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import { ContentPage } from '@/ui/components/layout';
import { Button } from '@/ui/components/primitives';
import { MonthSection } from './MonthSection';

// `/grilles` archive page. The route loader hands us the first page
// (newest 100 dailies); we keep appending older pages as the player
// requests them with "Charger mois précédent". Stays a single-section
// component so the route file stays a thin shell.
export interface GrillesPageProps {
  readonly initialPage: DailySummariesPage;
  readonly puzzleRepository: PuzzleRepository;
  readonly soloEntriesStore: SoloEntriesStore;
  // `YYYY-MM-DD` — anything older has no archived puzzle. Defaults to
  // the production launch anchor; tests inject their own.
  readonly launchAnchor?: string;
}

const srOnly = css({
  position: 'absolute',
  width: '1px',
  height: '1px',
  padding: 0,
  margin: '-1px',
  overflow: 'hidden',
  clip: 'rect(0, 0, 0, 0)',
  whiteSpace: 'nowrap',
  border: 0,
});

const loadMoreStyles = css({
  alignSelf: 'center',
  marginBlock: 'lg',
});

const emptyStatusStyles = css({
  color: 'fgMuted',
  textAlign: 'center',
  margin: 0,
});

const errorStyles = css({
  color: 'errorText',
  textAlign: 'center',
  margin: 0,
});

interface MonthBucket {
  readonly month: string;
  readonly slug: string;
  readonly rows: DailySummary[];
}

// Items arrive DESC; preserve order so MonthSection renders newest-first.
function groupByMonth(items: ReadonlyArray<DailySummary>): MonthBucket[] {
  const buckets = new Map<string, MonthBucket>();
  for (const it of items) {
    const d = new Date(`${it.date}T00:00:00Z`);
    const monthLabelRaw = new Intl.DateTimeFormat('fr-FR', {
      month: 'long',
      year: 'numeric',
    }).format(d);
    const month = monthLabelRaw.charAt(0).toUpperCase() + monthLabelRaw.slice(1);
    const slug = `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
    let bucket = buckets.get(slug);
    if (bucket == null) {
      bucket = { month, slug, rows: [] };
      buckets.set(slug, bucket);
    }
    bucket.rows.push(it);
  }
  return Array.from(buckets.values());
}

function firstDayOfMonth(iso: string): string {
  return `${iso.slice(0, 7)}-01`;
}

function isoMinusOneDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

export function GrillesPage({
  initialPage,
  puzzleRepository,
  soloEntriesStore,
  launchAnchor = '2026-01-01',
}: GrillesPageProps) {
  const [items, setItems] = useState<DailySummary[]>([...initialPage.items]);
  const [hasMore, setHasMore] = useState<boolean>(initialPage.hasMore);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const newRowCtaRef = useRef<HTMLAnchorElement | null>(null);
  const justLoadedRef = useRef(false);

  const oldest = items.length === 0 ? null : items[items.length - 1].date;
  const canLoadOlder = oldest != null && oldest > launchAnchor;
  const showLoadMore = hasMore || canLoadOlder;

  const loadOlder = useCallback(async () => {
    if (oldest == null) return;
    setLoading(true);
    setError(null);
    try {
      const nextTo = isoMinusOneDay(oldest);
      const candidate = firstDayOfMonth(nextTo);
      const nextFrom = candidate < launchAnchor ? launchAnchor : candidate;
      const page = await puzzleRepository.listDailySummaries({
        from: nextFrom,
        to: nextTo,
      });
      setItems((prev) => [...prev, ...page.items]);
      setHasMore(page.hasMore);
      justLoadedRef.current = page.items.length > 0;
    } catch {
      setError('Impossible de charger le mois précédent. Réessayez.');
    } finally {
      setLoading(false);
    }
  }, [oldest, puzzleRepository, launchAnchor]);

  useEffect(() => {
    if (justLoadedRef.current && newRowCtaRef.current) {
      newRowCtaRef.current.focus();
      justLoadedRef.current = false;
    }
  });

  const groups = groupByMonth(items);

  return (
    <ContentPage>
      <h1 className={srOnly}>Anciennes grilles</h1>
      {groups.length === 0 ? (
        <p className={emptyStatusStyles} role="status">Aucune grille disponible.</p>
      ) : (
        groups.map((g, gi) => (
          <MonthSection
            key={g.slug}
            month={g.month}
            slug={g.slug}
            rows={g.rows}
            soloEntriesStore={soloEntriesStore}
            // Focus ref attaches to the first row of the last (oldest)
            // bucket — the newly-appended content after a load-more.
            firstRowCtaRef={gi === groups.length - 1 ? newRowCtaRef : undefined}
          />
        ))
      )}
      {showLoadMore ? (
        <Button
          variant="ghost"
          onClick={() => { void loadOlder(); }}
          disabled={loading}
          className={loadMoreStyles}
        >
          {loading ? 'Chargement…' : 'Charger mois précédent'}
        </Button>
      ) : null}
      {error != null ? (
        <p role="alert" className={errorStyles}>{error}</p>
      ) : null}
    </ContentPage>
  );
}
