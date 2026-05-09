import { cx } from 'styled-system/css';
import { dotsStyles, dotBaseStyles, dotActiveStyles } from './soloTour.styles';

export interface PaginationDotsProps {
  readonly current: number;
  readonly total: number;
}

export function PaginationDots({ current, total }: PaginationDotsProps) {
  return (
    <div className={dotsStyles} aria-hidden="true">
      {Array.from({ length: total }).map((_, i) => (
        <span
          key={i}
          className={cx(dotBaseStyles, i === current ? dotActiveStyles : undefined)}
        />
      ))}
    </div>
  );
}
