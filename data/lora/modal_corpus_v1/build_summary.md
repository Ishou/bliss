# Modal corpus build summary — v1

- Build date (local): 2026-06-01
- Seed: 42
- Val ratio: 0.12
- Total rows after weighting + exclusion: 1292
- Train rows: 1137 | Val rows: 155

## Rows per source (after held-out exclusion, before weight replication)

| Source | Tier | Weight | Rows in | Rows out (× weight) |
|---|---|---:|---:|---:|
| `gold_pilot_v1` | gold | 4 | 114 | 456 |
| `curated_fr` | silver | 2 | 63 | 126 |
| `curated_short_fr` | silver | 2 | 239 | 478 |
| `synthetic_clues` | silver | 2 | 0 | 0 |
| `rated_iters_y` | bronze | 1 | 79 | 79 |
| `winners_round_10` | gold | 1 | 153 | 153 |
