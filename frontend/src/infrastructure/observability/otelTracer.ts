// Browser-side OpenTelemetry tracer (PR-F.2 / ADR-0033).
//
// Auto-instruments `fetch` and the initial document load, batches the
// resulting spans, and exports OTLP/HTTP to the public ingest endpoint
// (`otlp.wordsparrow.io`, see ADR-0033) which forwards in-cluster to
// the SigNoz collector. Spans carry a `traceparent` header on outbound
// fetches so they join the backend traces emitted by the Java agent
// on grid-api + game-api (ADR-0027 §6, PR-E).
//
// Configuration is **opt-in via env vars** (same idiom as
// `matomoTracker.ts`). When `VITE_OTEL_OTLP_ENDPOINT` is empty, this
// module is a no-op so dev / MSW preview / pre-PR-F.2 prod work
// unchanged.

import { trace } from '@opentelemetry/api';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  BatchSpanProcessor,
  TraceIdRatioBasedSampler,
  WebTracerProvider,
} from '@opentelemetry/sdk-trace-web';
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
} from '@opentelemetry/semantic-conventions';

export interface OtelTracerConfig {
  /** OTLP/HTTP endpoint base URL, e.g. `https://otlp.wordsparrow.io`. */
  readonly endpoint: string;
  /** Service identity surfaced in SigNoz under `service.name`. */
  readonly serviceName: string;
  /** Build-time version, surfaced as `service.version`. */
  readonly serviceVersion: string;
  /**
   * Trace sampling ratio in [0, 1]. ADR-0033 §2 picks 10% to keep
   * legitimate browser-side volume bounded; tune via env when traffic
   * patterns justify a different rate.
   */
  readonly samplerRatio: number;
}

/**
 * Read the tracer config from Vite-style env vars. Returns `null` when
 * `VITE_OTEL_OTLP_ENDPOINT` is empty so the caller can no-op cleanly.
 *
 * The env-var contract:
 *   - `VITE_OTEL_OTLP_ENDPOINT`     — base URL; empty disables tracing.
 *   - `VITE_OTEL_SAMPLER_RATIO`     — float in [0,1]; defaults to 0.1.
 *   - `VITE_OTEL_SERVICE_NAME`      — defaults to `frontend`.
 *   - `VITE_OTEL_SERVICE_VERSION`   — defaults to the bundled version.
 */
export function readOtelConfigFromEnv(): OtelTracerConfig | null {
  const endpoint = import.meta.env.VITE_OTEL_OTLP_ENDPOINT;
  if (!endpoint) return null;
  const ratio = Number(import.meta.env.VITE_OTEL_SAMPLER_RATIO ?? '0.1');
  return {
    endpoint,
    serviceName: import.meta.env.VITE_OTEL_SERVICE_NAME ?? 'frontend',
    serviceVersion: import.meta.env.VITE_OTEL_SERVICE_VERSION ?? '0.0.0',
    samplerRatio: Number.isFinite(ratio) && ratio >= 0 && ratio <= 1 ? ratio : 0.1,
  };
}

/**
 * Initialise the global tracer + register auto-instrumentations.
 * Idempotent — calling twice is harmless because `WebTracerProvider`
 * registration replaces any prior provider on the global API.
 *
 * Resource attributes mirror the backend pattern (ADR-0027 §6):
 *   - `service.name`     — same key the Java agent uses on grid/game
 *   - `service.version`  — bundled version, useful for "did this
 *                          regression appear after deploy X?"
 *   - `bounded_context`  — matches the Logback MDC field; lets traces
 *                          + logs join across ui ↔ api boundaries.
 *
 * Auto-instrumentations chosen for signal-to-cost ratio:
 *   - `FetchInstrumentation` — every backend call becomes a span,
 *     `traceparent` propagates so server spans nest under the browser
 *     fetch in SigNoz. The high-leverage signal.
 *   - `DocumentLoadInstrumentation` — initial page-load timing
 *     (DNS, TLS, TTFB, FCP). Cheap, fires once per session.
 *
 * Deliberately omitted:
 *   - `UserInteractionInstrumentation` — depends on `zone.js` and
 *     adds bundle weight without proportional value at our scale.
 *     Re-introduce once we have a specific click-flow we want to
 *     trace.
 *   - `XMLHttpRequestInstrumentation` — frontend uses `fetch`
 *     exclusively (verified via `git grep XMLHttpRequest`); the
 *     instrumentation is dead weight here.
 */
export function initOtelTracer(config: OtelTracerConfig | null): void {
  if (!config) return;
  if (typeof window === 'undefined') return;

  const exporter = new OTLPTraceExporter({
    url: `${config.endpoint.replace(/\/$/, '')}/v1/traces`,
  });

  const provider = new WebTracerProvider({
    sampler: new TraceIdRatioBasedSampler(config.samplerRatio),
    resource: resourceFromAttributes({
      [ATTR_SERVICE_NAME]: config.serviceName,
      [ATTR_SERVICE_VERSION]: config.serviceVersion,
      bounded_context: 'ui',
    }),
    spanProcessors: [new BatchSpanProcessor(exporter)],
  });

  provider.register();

  registerInstrumentations({
    instrumentations: [
      new FetchInstrumentation({
        // Propagate W3C traceparent only to our own APIs. Adding it on
        // cross-origin fetches to third-party hosts (Matomo, fonts CDN)
        // is harmless but bloats their access logs; scope it.
        propagateTraceHeaderCorsUrls: [
          /^https?:\/\/(?:[^/]+\.)?wordsparrow\.io(?:\/.*)?$/,
          /^https?:\/\/localhost(?::\d+)?(?:\/.*)?$/,
        ],
        // Drop the OTLP exporter's own POST from the trace stream —
        // otherwise every fetch instrumentation creates a span for the
        // span export call, which itself triggers another span, etc.
        // OTel doesn't auto-detect this loop, so filter it explicitly.
        ignoreUrls: [/\/v1\/traces$/],
      }),
      new DocumentLoadInstrumentation(),
    ],
    tracerProvider: provider,
  });

  // Surface a tracer for any future manual spans (none today).
  trace.getTracer(config.serviceName, config.serviceVersion);
}
