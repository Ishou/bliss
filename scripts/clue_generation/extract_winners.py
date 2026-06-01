"""Survey DB → winners CSV extractor for RAFT round-N fine-tuning (read-only)."""

from __future__ import annotations

import argparse
import csv
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Sequence


# RAFT winners scope to the last campaign; correctifs are cumulative gold, deliberately not campaign-scoped.
SQL = """
SELECT si.mot, si.definition, si.pos, si.categorie, si.style,
       si.force_claimed, si.longueur, si.source, si.source_batch,
       si.training_weight,
       r.qualite, r.created_at
  FROM survey_items si
  JOIN ratings r ON r.item_id = si.item_id
 WHERE r.user_id IN (SELECT user_id FROM maintainer_roles WHERE role = 'maintainer')
   AND r.qualite = 5
   AND r.flag IS NULL
   AND r.proposed_item_id IS NULL
   AND si.retired_at IS NULL
   AND ((si.source_batch LIKE %s AND r.campaign_id = %s::uuid)
        OR si.source = 'rater_proposed')
 ORDER BY r.created_at
"""

LAST_CAMPAIGN_SQL = """
SELECT campaign_id, batch_label, closed_at
  FROM campaigns
 ORDER BY opened_at DESC
 LIMIT 1
"""

COLUMNS = (
    "mot", "definition", "pos", "categorie", "style",
    "force_claimed", "longueur", "source", "source_batch",
    "training_weight",
    "qualite", "created_at",
)

# `;` delimiter matches gold_pilot_v1.csv and the manifest's csv_delimiter convention.
CSV_FIELDS = (
    "mot", "definition", "pos", "categorie", "style",
    "force", "longueur", "source", "source_batch", "rated_at",
    "training_weight",
)
CSV_DELIMITER = ";"


def _load_db_url() -> str:
    """Resolve SURVEY_DB_URL from env, else ~/.bliss/survey-db-url; error otherwise."""
    env = os.environ.get("SURVEY_DB_URL", "").strip()
    if env:
        return env
    fallback = Path.home() / ".bliss" / "survey-db-url"
    if fallback.is_file():
        text = fallback.read_text(encoding="utf-8").strip()
        if text:
            return text
    raise SystemExit(
        "ERROR: no DB URL — set SURVEY_DB_URL or write a libpq URL to "
        "~/.bliss/survey-db-url (read-only credentials only)."
    )


def resolve_last_campaign(conn: Any) -> tuple | None:
    """Return (campaign_id, batch_label, closed_at) for the most-recently-opened campaign, or None."""
    with conn.cursor() as cur:
        cur.execute(LAST_CAMPAIGN_SQL, ())
        rows = list(cur.fetchall())
    return rows[0] if rows else None


def fetch_rows(conn: Any, round_n: int, campaign_id: str) -> list[tuple]:
    """Execute the winners query and return all rows. RAFT rows are scoped to campaign_id."""
    with conn.cursor() as cur:
        cur.execute(SQL, (f"%-r{round_n}-%", campaign_id))
        return list(cur.fetchall())


def row_to_entry(row: Sequence) -> dict:
    """Map a DB row to the CSV dict; the SQL has already filtered round / source."""
    rec = dict(zip(COLUMNS, row))
    created_at = rec["created_at"]
    rated_at = created_at.isoformat() if hasattr(created_at, "isoformat") else str(created_at)
    return {
        "mot": rec["mot"],
        "definition": rec["definition"],
        "pos": rec["pos"],
        "categorie": rec["categorie"],
        "style": rec["style"],
        "force": rec["force_claimed"],
        "longueur": rec["longueur"],
        "source": rec["source"],
        "source_batch": rec["source_batch"],
        "rated_at": rated_at,
        "training_weight": rec["training_weight"],
    }


def write_csv(rows: Iterable[Sequence], out_path: Path) -> dict:
    """Serialize matching rows to `;`-delimited CSV; return kept count + per-source breakdown."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    kept = 0
    by_source: Counter[str] = Counter()
    with out_path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_FIELDS, delimiter=CSV_DELIMITER)
        writer.writeheader()
        for row in rows:
            entry = row_to_entry(row)
            writer.writerow(entry)
            by_source[entry["source"]] += 1
            kept += 1
    return {"kept": kept, "by_source": dict(by_source)}


def _print_summary(considered: int, result: dict, round_n: int, out_path: Path, batch_label: str) -> None:
    """Emit a one-screen summary to stderr."""
    print(f"campaign: {batch_label} (closed)", file=sys.stderr)
    print(f"survey_items × ratings rows considered: {considered}", file=sys.stderr)
    print(f"  kept (qualite=5, flag/proposed NULL, maintainer rater, "
          f"r{round_n} batch in campaign OR rater_proposed, unretired): {result['kept']}", file=sys.stderr)
    for src, count in sorted(result["by_source"].items()):
        print(f"    {src}: {count}", file=sys.stderr)
    print(f"wrote {out_path}", file=sys.stderr)


def run(round_n: int, out_path: Path, conn: Any) -> dict:
    """Fetch + serialize + summarize. Refuses unless the latest campaign is closed. Returns the result dict."""
    campaign = resolve_last_campaign(conn)
    if campaign is None:
        raise SystemExit("ERROR: no campaign exists; cannot scope winners to a campaign window (ADR-0059).")
    campaign_id, batch_label, closed_at = campaign
    if closed_at is None:
        raise SystemExit(
            f"ERROR: the latest campaign '{batch_label}' is still open. Close it before extracting "
            f"winners: UPDATE campaigns SET closed_at = now() WHERE closed_at IS NULL; (ADR-0059)."
        )
    rows = fetch_rows(conn, round_n, campaign_id)
    result = write_csv(rows, out_path)
    _print_summary(len(rows), result, round_n, out_path, batch_label)
    return result


def _connect_psycopg2(db_url: str) -> Any:
    """Open a psycopg2 connection; imported lazily so tests can stub."""
    import psycopg2
    return psycopg2.connect(db_url)


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--round", type=int, required=True,
                        help="Round number; matched against survey_items.source_batch via '-r<N>-' lineage tag.")
    parser.add_argument("--out", type=Path, required=True,
                        help="Output CSV path (e.g., data/lora/modal_corpus_v1/winners_round_10.csv).")
    args = parser.parse_args(argv)

    db_url = _load_db_url()
    conn = _connect_psycopg2(db_url)
    try:
        run(args.round, args.out, conn)
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
