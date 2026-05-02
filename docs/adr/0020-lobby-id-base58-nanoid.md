# ADR-0020: Lobby Identifier — base58 nanoid Exception to ADR-0003 §6

## Status

Accepted

## Context

ADR-0003 §6 mandates UUID v7 strings as the identifier format for all publicly
exposed resources. `LobbyId` deviates from this rule: it is an 8-character
base58-encoded nanoid (`^[1-9A-HJ-NP-Za-km-z]{8}$`) instead of a UUID v7.

The lobby identifier is embedded directly in the WebSocket URL path:

```
/v1/lobbies/{lobbyId}/ws
```

It also appears in share links that players copy and send to friends. Two
concerns drive the deviation:

1. **URL legibility.** A UUID v7 (`0190e3a4-7a2c-7c9e-8f1a-9b2d3e4f5a6b`) in
   a share link is 36 characters. An 8-char base58 nanoid (`7gQ2xK9p`) is
   short enough to read aloud, type on a phone, or fit in a chat message
   without wrapping.

2. **Collision probability.** 8 characters from a 57-symbol base58 alphabet
   gives 57^8 ≈ 1.1 × 10^14 distinct values. For the anticipated concurrent
   lobby count (≪ 10^6), the birthday collision probability is negligible
   without the overhead of a sortable 128-bit identifier.

UUID v7 was considered and rejected for `LobbyId`:

- **URL-safe base64url-encoded UUID v7** is 22 characters — shorter than the
  standard form but still not human-friendly in share links.
- **UUID v7's time-sortability** provides no benefit for lobbies: lobbies are
  looked up by exact ID, never listed in creation order.
- **Loss of ADR-0003 §6's uniformity** is a real cost; it is accepted here
  because `LobbyId` is the only resource that doubles as a human-visible share
  token.

## Decision

`LobbyId` uses an 8-character base58 nanoid, generated server-side on lobby
creation. The character set excludes visually ambiguous characters
(`0`, `O`, `I`, `l`) to minimise transcription errors.

This is an explicit, bounded exception to ADR-0003 §6:

- Only `LobbyId` is affected. All other identifiers (SessionId, puzzle ids,
  clue ids, etc.) remain UUID v7.
- A new bounded context may not silently inherit this exception; it must file
  its own ADR.

## Consequences

### Easier

- Share links are short and human-readable (`/v1/lobbies/7gQ2xK9p/ws`).
- No padding, hyphens, or encoding needed in URLs.

### Harder

- `LobbyId` cannot be time-sorted or monotonically ordered; pagination by
  creation time requires a separate `createdAt` timestamp column.
- Code that generically handles identifiers cannot assume UUID v7 format for
  `LobbyId`.
- Reviewers encountering `LobbyId` schemas must be directed here; the schema
  description now cites this ADR.
