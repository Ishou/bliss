// Layout-level constants shared across multiple files in this directory.
// Kept in a separate leaf module to avoid circular-import TDZ errors: both
// Page.tsx (which imports AppHeader/Footer) and AppHeader/Footer (which need
// PAGE_MAX_WIDTH) would form a cycle if the constant lived in Page.tsx.

// Single source of truth for the content-width cap (ADR-0036).
// Consumed by AppHeader, Footer, PrivacyNotice, and both Page variants.
export const PAGE_MAX_WIDTH = '720px';
