import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Capture handlers and the constructor options the SUT passes to Workbox.
const controllingHandlers: Array<() => void> = [];
const constructorCalls: Array<{ scriptUrl: string; options?: unknown }> = [];
const wbRegister = vi.fn(() => Promise.resolve());

vi.mock('workbox-window', () => ({
  Workbox: vi.fn().mockImplementation((scriptUrl: string, options?: unknown) => {
    constructorCalls.push({ scriptUrl, options });
    return {
      addEventListener: (type: string, fn: () => void) => {
        if (type === 'controlling') controllingHandlers.push(fn);
      },
      register: wbRegister,
    };
  }),
}));

import { registerServiceWorker } from '@/infrastructure/pwa';

const fireControlling = () => {
  for (const fn of controllingHandlers) fn();
};

const setVisibility = (state: 'visible' | 'hidden') => {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  });
};

describe('registerServiceWorker — update strategy', () => {
  let reloadMock: ReturnType<typeof vi.fn>;
  let originalLocation: Location;

  beforeEach(() => {
    controllingHandlers.length = 0;
    constructorCalls.length = 0;
    wbRegister.mockClear();

    reloadMock = vi.fn();
    originalLocation = window.location;
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...originalLocation, reload: reloadMock },
    });

    // jsdom doesn't expose `navigator.serviceWorker`; the SUT's
    // `'serviceWorker' in navigator` guard would otherwise short-
    // circuit registration. Any object satisfies the check — the SUT
    // doesn't read into `navigator.serviceWorker`, only the workbox-
    // window mock does (and we stub that wholesale).
    Object.defineProperty(navigator, 'serviceWorker', {
      configurable: true,
      value: {},
    });

    setVisibility('visible');

    // Production-like env so the early-returns don't short-circuit
    // registration.
    vi.stubEnv('DEV', false);
    vi.stubEnv('VITE_USE_MOCK_API', 'false');

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllEnvs();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: originalLocation,
    });
  });

  it("passes updateViaCache: 'none' to the Workbox registration", () => {
    registerServiceWorker();

    expect(constructorCalls).toHaveLength(1);
    expect(constructorCalls[0]!.scriptUrl).toBe('/sw.js');
    expect(constructorCalls[0]!.options).toMatchObject({ updateViaCache: 'none' });
  });

  it('reloads immediately when controlling fires within the fresh-load window', () => {
    registerServiceWorker();

    fireControlling();

    expect(reloadMock).toHaveBeenCalledTimes(1);
  });

  it('defers the reload when controlling fires after the fresh-load window', () => {
    registerServiceWorker();

    vi.advanceTimersByTime(5000);
    fireControlling();
    expect(reloadMock).not.toHaveBeenCalled();

    // Tab is still visible — the deferred reload waits for the next
    // hidden→visible transition before firing.
    setVisibility('hidden');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reloadMock).not.toHaveBeenCalled();

    setVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reloadMock).toHaveBeenCalledTimes(1);
  });

  it('defers the reload when the tab is hidden, even within the fresh-load window', () => {
    setVisibility('hidden');
    registerServiceWorker();

    fireControlling();
    expect(reloadMock).not.toHaveBeenCalled();

    setVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    expect(reloadMock).toHaveBeenCalledTimes(1);
  });

  it('does not reload twice when controlling fires repeatedly', () => {
    registerServiceWorker();

    fireControlling();
    fireControlling();
    fireControlling();

    expect(reloadMock).toHaveBeenCalledTimes(1);
  });

  it('arms only one deferred-reload listener even if controlling fires multiple times mid-session', () => {
    registerServiceWorker();

    vi.advanceTimersByTime(5000);
    fireControlling();
    fireControlling();

    setVisibility('visible');
    document.dispatchEvent(new Event('visibilitychange'));
    document.dispatchEvent(new Event('visibilitychange'));

    expect(reloadMock).toHaveBeenCalledTimes(1);
  });
});
