# survey scripts

## `backfill_campaigns.py`

One-shot historical-rating attribution. Run **after** V7 (`campaigns`) has
been deployed and **before** any follow-up migration that tightens
`campaign_id` to `NOT NULL`. See ADR-0059 and the companion spec §7.

### Dependencies

```bash
pip install psycopg uuid-utils
```

The script generates campaign UUIDs with `uuid_utils.uuid7()` so the cluster
does not need the `pg_uuidv7` extension installed; UUIDv7 values are passed
as bind parameters (ADR-0003 §6).

### Input

A CSV with one row per historical batch:

```csv
batch_label,opened_at,closed_at
round-5,2026-05-01T00:00:00,2026-05-07T00:00:00
round-6,2026-05-08T00:00:00,2026-05-15T00:00:00
```

Timestamps are interpreted as UTC regardless of the local timezone or the
PostgreSQL session's `TimeZone` setting. Use naive ISO 8601 (`T00:00:00`)
or explicit UTC offset (`T00:00:00+00:00`); either form is accepted.

Source the windows from Modal job logs: each batch run's `started_at` →
`finished_at`. Sort by `opened_at`; the script rejects overlapping
windows.

### Usage

```bash
python scripts/survey/backfill_campaigns.py \
    --dsn postgres://survey:***@host:5432/survey \
    --batches scripts/survey/historical_batches.csv \
    [--dry-run]
```

`--dry-run` rolls back the transaction at the end so you can audit the
coverage report before committing.

### Idempotency

Each step only touches the rows it has not stamped yet:

- `ensure_campaigns` skips labels that already exist.
- `stamp_ratings` and `stamp_pair_ratings` only touch rows where
  `campaign_id IS NULL`.

Re-running the script after partial failure is safe.

### Tests

```bash
pip install pytest testcontainers
pytest scripts/survey/test_backfill_campaigns.py
```

The integration tests spin up a Postgres container (Testcontainers) and
apply the survey Flyway migrations. When Docker is unavailable the suite
is skipped rather than failed.
