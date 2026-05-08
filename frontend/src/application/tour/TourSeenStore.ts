// In application/: tour-completion flag is a use-case concern (UX
// preference), not a domain invariant. The adapter lives in
// infrastructure/session/localStorageTour.ts and is wired by main.tsx
// into the router context, mirroring the SoloEntriesStore pattern.

export interface TourSeenStore {
  get(): boolean;
  set(seen: boolean): void;
  clear(): void;
}
