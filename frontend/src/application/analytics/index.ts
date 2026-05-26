// Application port for analytics event reporting (ADR-0025). Decouples
// `ui/` routes from the concrete Matomo adapter — routes call
// `analytics.trackEvent(...)` via router context; the composition root
// wires `createMatomoTracker` (or a no-op for tests).

export interface AnalyticsPort {
  trackEvent(category: string, action: string, name?: string, value?: number): void;
}

export const NOOP_ANALYTICS: AnalyticsPort = {
  trackEvent() {
    /* noop */
  },
};
