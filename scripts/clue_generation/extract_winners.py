"""Survey DB → winners JSONL extractor for RAFT round-N fine-tuning (read-only)."""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path
from typing import Any, Iterable, Sequence


SQL = """
SELECT si.mot, si.definition, si.pos, si.categorie, si.style,
       si.force_claimed, si.longueur, si.source, si.source_batch, si.expected,
       r.qualite, r.user_id, r.created_at
  FROM survey_items si
  JOIN ratings r ON r.item_id = si.item_id
 WHERE r.user_id = %s::uuid
   AND r.qualite = 5
   AND r.flag IS NULL
   AND si.retired_at IS NULL
   AND si.source_batch LIKE %s
 ORDER BY r.created_at
"""

COLUMNS = (
    "mot", "definition", "pos", "categorie", "style",
    "force_claimed", "longueur", "source", "source_batch", "expected",
    "qualite", "user_id", "created_at",
)


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


def fetch_rows(conn: Any, user_id: str, round_n: int) -> list[tuple]:
    """Execute the winners query and return all rows. Round is matched via source_batch lineage tag."""
    with conn.cursor() as cur:
        cur.execute(SQL, (user_id, f"%-r{round_n}-%"))
        return list(cur.fetchall())


def row_to_entry(row: Sequence, round_n: int) -> tuple[dict | None, str | None]:
    """Map a DB row to the JSONL dict; return (entry, drop_reason). Exactly one is None."""
    rec = dict(zip(COLUMNS, row))
    if f"-r{round_n}-" not in (rec["source_batch"] or ""):
        return None, "source_batch_round_mismatch"
    created_at = rec["created_at"]
    rated_at = created_at.isoformat() if hasattr(created_at, "isoformat") else str(created_at)
    entry = {
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
    }
    return entry, None


def write_jsonl(rows: Iterable[Sequence], out_path: Path, round_n: int) -> dict:
    """Serialize matching rows to JSONL; return per-reason drop counts + kept count."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    kept = 0
    drops: Counter[str] = Counter()
    with out_path.open("w", encoding="utf-8") as f:
        for row in rows:
            entry, reason = row_to_entry(row, round_n)
            if entry is None:
                drops[reason or "unknown"] += 1
                continue
            f.write(json.dumps(entry, ensure_ascii=False) + "\n")
            kept += 1
    return {"kept": kept, "drops": dict(drops)}


def _print_summary(considered: int, result: dict, round_n: int, out_path: Path) -> None:
    """Emit a one-screen summary to stderr."""
    drops = result["drops"]
    dropped_total = sum(drops.values())
    print(f"survey_items × ratings rows considered: {considered}", file=sys.stderr)
    print(f"  kept (qualite=5, flag IS NULL, source_batch=*-r{round_n}-*, unretired): "
          f"{result['kept']}", file=sys.stderr)
    print(f"  dropped: {dropped_total}", file=sys.stderr)
    for reason, count in sorted(drops.items()):
        print(f"    {reason}: {count}", file=sys.stderr)
    print(f"wrote {out_path}", file=sys.stderr)


def run(user_id: str, round_n: int, out_path: Path, conn: Any) -> dict:
    """Fetch + serialize + summarize. Returns the result dict."""
    rows = fetch_rows(conn, user_id, round_n)
    result = write_jsonl(rows, out_path, round_n)
    _print_summary(len(rows), result, round_n, out_path)
    return result


def _connect_psycopg2(db_url: str) -> Any:
    """Open a psycopg2 connection; imported lazily so tests can stub."""
    import psycopg2  # local import lets tests run without psycopg2 installed
    return psycopg2.connect(db_url)


def main(argv: list[str] | None = None) -> int:
    """CLI entrypoint."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--user-id", required=True,
                        help="Maintainer's user_id (UUID); single-rater training is intentional.")
    parser.add_argument("--round", type=int, required=True,
                        help="Round number; matched against survey_items.source_batch via '-r<N>-' lineage tag.")
    parser.add_argument("--out", type=Path, required=True,
                        help="Output JSONL path (e.g., data/lora/modal_corpus_v1/winners_round_1.jsonl).")
    args = parser.parse_args(argv)

    db_url = _load_db_url()
    conn = _connect_psycopg2(db_url)
    try:
        run(args.user_id, args.round, args.out, conn)
    finally:
        conn.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
