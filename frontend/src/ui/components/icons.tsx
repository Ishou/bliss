// Inline SVG icon set used by the toolbar / pills.
//
// Hand-rolled (no icon-library dep) so we keep the brand brief's exact
// stroke weights and the icons stay single-colour via `currentColor`. All
// icons take a 24×24 viewBox; the rendered size is driven by the parent
// (IconButton sets a 1em font-size; pills set width/height directly).

export interface IconProps {
  readonly className?: string;
}

const baseSvgProps = {
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.6,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  'aria-hidden': true,
  focusable: false as const,
};

export function ClockIcon({ className }: IconProps) {
  return (
    <svg {...baseSvgProps} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 7v5l3 2" />
    </svg>
  );
}

export function RefreshIcon({ className }: IconProps) {
  return (
    <svg {...baseSvgProps} className={className}>
      <path d="M20 12a8 8 0 1 1-2.34-5.66" />
      <path d="M20 4v4h-4" />
    </svg>
  );
}

export function HintIcon({ className }: IconProps) {
  return (
    <svg {...baseSvgProps} className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7" />
      <path d="M12 17h.01" />
    </svg>
  );
}

export function SettingsIcon({ className }: IconProps) {
  return (
    <svg {...baseSvgProps} className={className}>
      <circle cx="12" cy="12" r="2.6" />
      <path d="M19.4 14a1.6 1.6 0 0 0 .32 1.76l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.6 1.6 0 0 0-1.76-.32 1.6 1.6 0 0 0-.97 1.46V20a2 2 0 1 1-4 0v-.18a1.6 1.6 0 0 0-1.05-1.46 1.6 1.6 0 0 0-1.76.32l-.06.06A2 2 0 1 1 4.46 15.9l.06-.06A1.6 1.6 0 0 0 4.84 14a1.6 1.6 0 0 0-1.46-.97H3.2a2 2 0 1 1 0-4h.18A1.6 1.6 0 0 0 4.84 8a1.6 1.6 0 0 0-.32-1.76l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06A1.6 1.6 0 0 0 9.1 3.7 1.6 1.6 0 0 0 10.07 2.24V2.06a2 2 0 1 1 4 0v.18a1.6 1.6 0 0 0 .97 1.46 1.6 1.6 0 0 0 1.76-.32l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.6 1.6 0 0 0-.32 1.76V8a1.6 1.6 0 0 0 1.46.97h.18a2 2 0 1 1 0 4h-.18a1.6 1.6 0 0 0-1.46.97Z" />
    </svg>
  );
}
