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
  // Lightbulb: glass envelope (rounded), filament hint, threaded base.
  return (
    <svg {...baseSvgProps} className={className}>
      <path d="M9 18h6" />
      <path d="M10 21h4" />
      <path d="M12 3a6 6 0 0 0-3.5 10.9c.5.4.8.9.9 1.5l.1.6h5l.1-.6c.1-.6.4-1.1.9-1.5A6 6 0 0 0 12 3Z" />
    </svg>
  );
}

export function HamburgerIcon({ className }: IconProps) {
  // Three stacked horizontal lines — mobile nav trigger.
  return (
    <svg {...baseSvgProps} className={className}>
      <path d="M4 7h16" />
      <path d="M4 12h16" />
      <path d="M4 17h16" />
    </svg>
  );
}

export function MoreIcon({ className }: IconProps) {
  // Three horizontal dots — overflow menu trigger.
  return (
    <svg {...baseSvgProps} className={className}>
      <circle cx="5" cy="12" r="1" fill="currentColor" stroke="none" />
      <circle cx="12" cy="12" r="1" fill="currentColor" stroke="none" />
      <circle cx="19" cy="12" r="1" fill="currentColor" stroke="none" />
    </svg>
  );
}

export function RestartIcon({ className }: IconProps) {
  // Counter-clockwise arrow — used by the mobile overflow "Recommencer" item.
  return (
    <svg {...baseSvgProps} className={className}>
      <path d="M4 12a8 8 0 1 0 2.34-5.66" />
      <path d="M4 4v4h4" />
    </svg>
  );
}

export function EyeIcon({ className }: IconProps) {
  // Open eye — "show / reveal" affordance for the lobby code chip and
  // the Accueil PIN input. Lucide-style stroke-only outline.
  return (
    <svg {...baseSvgProps} className={className}>
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12Z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

export function EyeOffIcon({ className }: IconProps) {
  // Crossed-out eye — "hide / mask" affordance. Pairs with EyeIcon.
  return (
    <svg {...baseSvgProps} className={className}>
      <path d="M3 3l18 18" />
      <path d="M10.6 6.1A10.5 10.5 0 0 1 12 6c6.5 0 10 6 10 6a17.7 17.7 0 0 1-3.1 3.5" />
      <path d="M6.6 6.6A17.6 17.6 0 0 0 2 12s3.5 7 10 7a10.5 10.5 0 0 0 4.6-1.05" />
      <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2" />
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
