// Browser-side OpenTelemetry tracer (PR-F.2 / ADR-0033, extended in PR-F.3).
//
// Auto-instruments `fetch` and the initial document load, batches the
// resulting spans, and exports OTLP/HTTP to the public ingest endpoint
// (`otlp.wordsparrow.io`, see ADR-0033) which forwards in-cluster to
// the SigNoz collector. Spans carry a `traceparent` header on outbound
// fetches so they join the backend traces emitted by the Java agent
// on grid-api + game-api (ADR-0027 §6, PR-E).
//
// PR-F.3 adds capture for uncaught JS exceptions and unhandled promise
// rejections — surfaced as spans with `status.code = ERROR` so they
// trip the `frontend-error-rate-high` SigNoz alert
// (`infra/observability/alerts/frontend-error-rate.md`). Error spans
// always sample regardless of the configured ratio (errors are rare
// and high-signal; sampling them away defeats the point).
//
// Configuration is **opt-in via env vars** (same idiom as
// `matomoTracker.ts`). When `VITE_OTEL_OTLP_ENDPOINT` is empty, this
// module is a no-op so dev / MSW preview / pre-PR-F.2 prod work
// unchanged.

import { SpanStatusCode, trace, type Tracer } from '@opentelemetry/api';
import { OTLPTraceExporter } from '@opentelemetry/exporter-trace-otlp-http';
import { registerInstrumentations } from '@opentelemetry/instrumentation';
import { DocumentLoadInstrumentation } from '@opentelemetry/instrumentation-document-load';
import { FetchInstrumentation } from '@opentelemetry/instrumentation-fetch';
import { resourceFromAttributes } from '@opentelemetry/resources';
import {
  AlwaysOnSampler,
  BatchSpanProcessor,
  ParentBasedSampler,
  SamplingDecision,
  TraceIdRatioBasedSampler,
  WebTracerProvider,
  type Sampler,
  type SamplingResult,
} from '@opentelemetry/sdk-trace-web';
import {
  ATTR_SERVICE_NAME,
  ATTR_SERVICE_VERSION,
} from '@opentelemetry/semantic-conventions';

/** Span name prefix for uncaught-error captures. Always sampled. */
const ERROR_SPAN_NAME_PREFIX = 'window.';

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
   * patterns justify a different rate. Error spans bypass this ratio.
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
 * Sampler that always-samples spans whose name starts with
 * {@link ERROR_SPAN_NAME_PREFIX} (the uncaught-error captures emitted
 * by `attachUncaughtErrorReporting`) and otherwise delegates to a base
 * sampler. Errors are rare + high-signal; sampling them away defeats
 * the purpose of capturing them.
 */
class ErrorAwareSampler implements Sampler {
  private readonly base: Sampler;

  constructor(base: Sampler) {
    this.base = base;
  }

  shouldSample(
    ...args: Parameters<Sampler['shouldSample']>
  ): SamplingResult {
    const spanName = args[2];
    if (spanName.startsWith(ERROR_SPAN_NAME_PREFIX)) {
      return { decision: SamplingDecision.RECORD_AND_SAMPLED };
    }
    return this.base.shouldSample(...args);
  }

  toString(): string {
    return `ErrorAwareSampler(${this.base.toString()})`;
  }
}

/**
 * Wire `window.error` and `window.unhandledrejection` to emit OTel
 * spans with `status.code = ERROR`. Captures are scoped tight: just
 * the message, source location, and stack — no DOM contents, no
 * cookies, no localStorage. ADR-0027 §8 redaction posture.
 */
function attachUncaughtErrorReporting(tracer: Tracer): void {
  window.addEventListener('error', (event) => {
    const span = tracer.startSpan(`${ERROR_SPAN_NAME_PREFIX}error`, {
      attributes: {
        'exception.type': event.error?.name ?? 'Error',
        'exception.message': event.message,
        'exception.stacktrace': event.error?.stack,
        'code.filepath': event.filename,
        'code.lineno': event.lineno,
        'code.column': event.colno,
      },
    });
    span.setStatus({ code: SpanStatusCode.ERROR, message: event.message });
    span.end();
  });

  window.addEventListener('unhandledrejection', (event) => {
    const reason: unknown = event.reason;
    const isError = reason instanceof Error;
    const span = tracer.startSpan(`${ERROR_SPAN_NAME_PREFIX}unhandledrejection`, {
      attributes: {
        'exception.type': isError ? reason.name : typeof reason,
        'exception.message': isError
          ? reason.message
          : String(reason ?? 'unknown'),
        'exception.stacktrace': isError ? reason.stack : undefined,
      },
    });
    span.setStatus({
      code: SpanStatusCode.ERROR,
      message: isError ? reason.message : 'unhandled rejection',
    });
    span.end();
  });
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
 * Plus PR-F.3's window-level error capture, which is not an
 * instrumentation but a global listener that emits spans directly.
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

  // Sampling stack:
  //  - ErrorAwareSampler always-on for window.* spans (PR-F.3)
  //  - ParentBased so child spans inherit the parent's decision (a
  //    document-load + fetch graph stays consistent)
  //  - Root-level TraceIdRatioBasedSampler at the configured ratio
  //  - Remote-parent samplers default to AlwaysOn so backend-initiated
  //    traces (e.g. WebSocket message turns) carry through.
  const baseSampler = new ParentBasedSampler({
    root: new TraceIdRatioBasedSampler(config.samplerRatio),
    remoteParentSampled: new AlwaysOnSampler(),
  });

  const provider = new WebTracerProvider({
    sampler: new ErrorAwareSampler(baseSampler),
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

  // Tracer for window-level error captures (PR-F.3).
  attachUncaughtErrorReporting(
    trace.getTracer(config.serviceName, config.serviceVersion),
  );
}
