# Modal jobs — cloud-GPU clue-AI training lane

Implements ADR-0057. **Training only.** Production inference path is
local + committed CSV per ADR-0013 §8.

## One-time setup (per developer)

1. Install Modal CLI: `pip install modal-client`.
2. Authenticate: `modal token new`.
3. Accept the Mistral licence on HF Hub (same account whose token
   you'll upload). Visit
   <https://huggingface.co/mistralai/Mistral-Nemo-Base-2407>, click
   "Accept" on the licence prompt.
4. Create the Modal secret:
   `modal secret create huggingface HF_TOKEN=hf_xxxx`.

## Paliers (run in order on first install)

| # | Script | Cost | Time | Validates |
|---|--------|------|------|-----------|
| 0 | `modal run modal_jobs/00_hello_world.py` | ~$0.01 | ~10 s | Modal CLI + auth |
| 1 | `modal run modal_jobs/01_gpu_check.py` | ~$0.03 | ~30 s | A100-40GB available |
| 2 | `modal run modal_jobs/02_download_mistral.py` | ~$0.05 | 5–15 min (first time) | `huggingface` secret + licence accept + volume persistence |
| 3a | `modal run modal_jobs/03a_upload_dataset.py` | ~$0.01 | ~15 s | dataset volume reachable from local |
| 3b | `modal run modal_jobs/03b_finetune.py` | ~$1.50 | ~25–35 min | end-to-end QLoRA SFT |

**Palier 3a default mode** is `--mode fused` (reads
`data/lora/modal_corpus_v1/train.jsonl` + `val.jsonl`, the multi-source
corpus produced by `scripts/clue_generation/modal/build_modal_corpus`).
For a smoke test against the gold pilot alone, pass `--mode gold-only`
which reads `data/seed/gold_pilot_v1_train.jsonl` + `val.jsonl` (output
of `scripts/clue_generation/modal/prepare_dataset.py`). Volume-side
filenames are stable (`/datasets/train.jsonl` + `/datasets/val.jsonl`)
so palier 3b is mode-agnostic.

After palier 2, the model lives on volume `mots-fleches-models` and
is reused by every subsequent run (idempotent — re-running 02 reports
`skipped` immediately). After palier 3b, the trained adapter lives on
volume `mots-fleches-adapters` at `/adapters/mistral-nemo-pilot-v1`.

## Killing a runaway run

```
modal app stop mots-fleches-finetune
```

(replace app name per the script — `mots-fleches-download` for
palier 2, `mots-fleches-upload-dataset` for 3a, etc.)

Each palier also has a defensive `timeout=` (1800 s palier 2, 3600 s
palier 3b) — a runaway dies on its own within an hour.

## Costs

A100-40GB on Modal is $3.10/hr at the time of ADR-0057. Pilot
end-to-end run total ≈ $1.50. Storage on `mots-fleches-models`
volume ≈ $0.13/month for ~24 GB. CPU/network during paliers 0/1/3a is
fractions of a cent per run.
