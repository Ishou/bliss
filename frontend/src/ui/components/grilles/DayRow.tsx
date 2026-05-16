import type { Ref } from 'react';
import { Link } from '@tanstack/react-router';
import { css } from 'styled-system/css';
import type { DailySummary } from '@/application';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import { ProgressBar } from '@/ui/components/layout';

// Single archive row — one card per past daily. Reuses the Accueil
// card visual language so the progress + CTA derivation reads the
// same way the player learned on the home page.
export interface DayRowProps {
  readonly summary: DailySummary;
  readonly soloEntriesStore: SoloEntriesStore;
  // Forwarded to the CTA `<Link>` so GrillesPage can focus the first
  // newly-appended row after a successful "Charger mois précédent".
  readonly ctaRef?: Ref<HTMLAnchorElement>;
}

const cardStyles = css({
  bg: 'surface',
  borderRadius: 'md',
  padding: 'md',
  border: '1px solid token(colors.border)',
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
});

const titleStyles = css({
  fontSize: 'lg',
  fontWeight: 'semibold',
  margin: 0,
  color: 'fg',
});

const ctaStyles = css({
  alignSelf: 'flex-end',
  fontSize: 'sm',
  color: 'accent',
  textDecoration: 'none',
  padding: 'xs',
  _hover: { textDecoration: 'underline' },
  _focusVisible: {
    outline: '2px solid token(colors.focusRing)',
    outlineOffset: '2px',
    borderRadius: '2px',
  },
});

// Capitalised French long date — "Mardi 5 mai" — matching the Accueil card.
function formatLongDateFr(date: string): string {
  const formatted = new Intl.DateTimeFormat('fr-FR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
  }).format(new Date(`${date}T00:00:00Z`));
  return formatted.charAt(0).toUpperCase() + formatted.slice(1);
}

export function DayRow({ summary, soloEntriesStore, ctaRef }: DayRowProps) {
  const locked = soloEntriesStore.loadLockedCells(summary.id);
  const entries = soloEntriesStore.load(summary.id);
  const lockedKeys = new Set(locked.map((c) => `${c.row},${c.column}`));
  const pending = entries.reduce(
    (n, e) => (lockedKeys.has(`${e.row},${e.column}`) ? n : n + 1),
    0,
  );
  const lockedCount = locked.length;
  const total = summary.totalLetterCells;

  let cta: 'Commencer' | 'Reprendre' | 'Revoir';
  if (lockedCount === 0 && pending === 0) cta = 'Commencer';
  else if (lockedCount < total) cta = 'Reprendre';
  else cta = 'Revoir';

  const heading = `${formatLongDateFr(summary.date)} · n°${summary.gridNumber}`;
  const headingId = `grilles-row-${summary.date}`;

  return (
    <article className={cardStyles} aria-labelledby={headingId}>
      <h3 id={headingId} className={titleStyles}>{heading}</h3>
      {total > 0 ? (
        <ProgressBar value={lockedCount} total={total} pending={pending} showLabel={false} />
      ) : null}
      <Link
        ref={ctaRef}
        aria-label={`${cta} la grille du ${formatLongDateFr(summary.date)} · n°${summary.gridNumber}`}
        to="/grille"
        search={{ date: summary.date }}
        className={ctaStyles}
      >
        <span aria-hidden="true">{cta} →</span>
      </Link>
    </article>
  );
}
