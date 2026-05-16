import type { Ref } from 'react';
import { css } from 'styled-system/css';
import type { DailySummary } from '@/application';
import type { SoloEntriesStore } from '@/application/solo/SoloEntriesStore';
import { DayRow } from './DayRow';

// Month-grouped archive section. The list stays a sequence of cards
// (rather than a `<ul>`) because each `DayRow` already carries semantic
// `<article>` heading structure — wrapping them in list semantics adds
// noise for screen readers without aiding navigation.
export interface MonthSectionProps {
  readonly month: string;
  readonly slug: string;
  readonly rows: ReadonlyArray<DailySummary>;
  readonly soloEntriesStore: SoloEntriesStore;
  // Forwarded to the first DayRow's CTA — GrillesPage threads this to
  // the first row of the newest bucket so post-load focus lands on the
  // freshly-appended content.
  readonly firstRowCtaRef?: Ref<HTMLAnchorElement>;
}

const sectionStyles = css({
  display: 'flex',
  flexDirection: 'column',
  gap: 'sm',
  marginBlock: 'md',
});

const headingStyles = css({
  fontSize: 'lg',
  fontWeight: 'semibold',
  color: 'fgMuted',
  margin: 0,
  marginBottom: 'xs',
});

export function MonthSection({
  month,
  slug,
  rows,
  soloEntriesStore,
  firstRowCtaRef,
}: MonthSectionProps) {
  const id = `grilles-month-${slug}`;
  return (
    <section className={sectionStyles} aria-labelledby={id}>
      <h2 id={id} className={headingStyles}>{month}</h2>
      {rows.map((row, idx) => (
        <DayRow
          key={row.id}
          summary={row}
          soloEntriesStore={soloEntriesStore}
          ctaRef={idx === 0 ? firstRowCtaRef : undefined}
        />
      ))}
    </section>
  );
}
