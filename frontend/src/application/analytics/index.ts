// Application port for analytics event reporting (ADR-0025).

export interface AnalyticsPort {
  trackEvent(category: string, action: string, name?: string, value?: number): void;
}

export const NOOP_ANALYTICS: AnalyticsPort = {
  trackEvent() {
    /* noop */
  },
};
