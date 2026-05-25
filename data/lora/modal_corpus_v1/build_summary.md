# Modal corpus build summary — v1

- Build date (local): 2026-05-26
- Seed: 42
- Val ratio: 0.12
- Total rows after weighting + exclusion: 1073
- Train rows: 944 | Val rows: 129

## Rows per source (after held-out exclusion, before weight replication)

| Source | Tier | Weight | Rows in | Rows out (× weight) |
|---|---|---:|---:|---:|
| `gold_pilot_v1` | gold | 4 | 114 | 456 |
| `curated_fr` | silver | 2 | 50 | 100 |
| `curated_short_fr` | silver | 2 | 234 | 468 |
| `synthetic_clues` | silver | 2 | 0 | 0 |
| `rated_iters_y` | bronze | 1 | 49 | 49 |
