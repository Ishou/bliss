import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// Capture handlers and the constructor options the SUT passes to Workbox.
const controllingHandlers: Array<() => void> = [];
const constructorCalls: Array<{ scriptUrl: string; options?: unknown }> = [];
const wbRegister = vi.fn(() => Promise.resolve());

// Vitest 4 narrowed `vi.fn().mockImplementation(arrow)` so the resulting
// mock is no longer constructable (`new MockedFn(...)` throws "is not a
// constructor"). Use a regular `function` expression so it carries its
// own `[[Construct]]` slot. Same observable behaviour, different shape.
vi.mock('workbox-window', () => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  Workbox: vi.fn(function (this: any, scriptUrl: string, options?: unknown) {
    constructorCalls.push({ scriptUrl, options });
    this.addEventListener = (type: string, fn: () => void) => {
      if (type === 'controlling') controllingHandlers.push(fn);
    };
    this.register = wbRegister;
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
    vi.stubEnv('VITE_MOCK_GRID_API', 'false');
    vi.stubEnv('VITE_MOCK_GAME_API', 'false');

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

  it('reloads once on vite:preloadError', () => {
    sessionStorage.clear();
    registerServiceWorker();
    window.dispatchEvent(new Event('vite:preloadError'));
    expect(reloadMock).toHaveBeenCalledTimes(1);
  });

  it('does not reload a second time when the session flag is already set', () => {
    sessionStorage.setItem('bliss.chunk-mismatch-reload', '1');
    registerServiceWorker();
    window.dispatchEvent(new Event('vite:preloadError'));
    expect(reloadMock).not.toHaveBeenCalled();
  });

  // Preview deploys set VITE_MOCK_GRID_API/VITE_MOCK_GAME_API='true' so
  // MSW's own service worker takes scope `/`. Registering Workbox here
  // would race MSW for that scope; the resulting `controlling` event
  // would fire reloadOnce() inside the fresh-load window, triggering an
  // infinite reload loop on every preview URL (regression caught on
  // https://11593b5f.bliss-cb4.pages.dev/ — page refreshed every ~1.5 s).
  it('skips registration when VITE_MOCK_GRID_API is true', () => {
    vi.stubEnv('VITE_MOCK_GRID_API', 'true');
    registerServiceWorker();
    expect(constructorCalls).toHaveLength(0);
  });

  it('skips registration when VITE_MOCK_GAME_API is true', () => {
    vi.stubEnv('VITE_MOCK_GAME_API', 'true');
    registerServiceWorker();
    expect(constructorCalls).toHaveLength(0);
  });
});
