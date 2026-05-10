import { afterEach, describe, expect, it, vi } from 'vitest';
import { readOtelConfigFromEnv } from '@/infrastructure/observability/otelTracer';

describe('readOtelConfigFromEnv', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('returns null when VITE_OTEL_OTLP_ENDPOINT is empty', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', '');

    expect(readOtelConfigFromEnv()).toBeNull();
  });

  it('returns null when VITE_OTEL_OTLP_ENDPOINT is absent', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', undefined as unknown as string);

    expect(readOtelConfigFromEnv()).toBeNull();
  });

  it('returns config with the endpoint when VITE_OTEL_OTLP_ENDPOINT is set', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');
    vi.stubEnv('VITE_OTEL_SAMPLER_RATIO', '0.5');

    const config = readOtelConfigFromEnv();

    expect(config).not.toBeNull();
    expect(config!.endpoint).toBe('https://otlp.example.io');
    expect(config!.samplerRatio).toBe(0.5);
  });

  it('accepts a valid ratio of 0.5', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');
    vi.stubEnv('VITE_OTEL_SAMPLER_RATIO', '0.5');

    expect(readOtelConfigFromEnv()!.samplerRatio).toBe(0.5);
  });

  it('falls back to 0.1 for a negative ratio', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');
    vi.stubEnv('VITE_OTEL_SAMPLER_RATIO', '-1');

    expect(readOtelConfigFromEnv()!.samplerRatio).toBe(0.1);
  });

  it('falls back to 0.1 for a ratio greater than 1', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');
    vi.stubEnv('VITE_OTEL_SAMPLER_RATIO', '2');

    expect(readOtelConfigFromEnv()!.samplerRatio).toBe(0.1);
  });

  it('falls back to 0.1 for a non-numeric ratio string', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');
    vi.stubEnv('VITE_OTEL_SAMPLER_RATIO', 'abc');

    expect(readOtelConfigFromEnv()!.samplerRatio).toBe(0.1);
  });

  it('defaults serviceName to "frontend" when unset', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');

    expect(readOtelConfigFromEnv()!.serviceName).toBe('frontend');
  });

  it('uses VITE_OTEL_SERVICE_NAME when provided', () => {
    vi.stubEnv('VITE_OTEL_OTLP_ENDPOINT', 'https://otlp.example.io');
    vi.stubEnv('VITE_OTEL_SERVICE_NAME', 'my-service');

    expect(readOtelConfigFromEnv()!.serviceName).toBe('my-service');
  });
});
