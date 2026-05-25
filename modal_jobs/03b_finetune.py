"""Palier 3b — Fine-tune QLoRA pédagogique de Mistral Nemo Base 2407.

OBJECTIF
--------
Valider l'infrastructure end-to-end de fine-tune sur Modal :
chargement 4-bit du modèle, training SFTTrainer, sauvegarde adapters
LoRA. **Pas d'objectif qualité** — 114 entrées sont insuffisantes pour
ajuster utilement 12 milliards de paramètres. Le pilote prouve que la
chaîne tourne ; la qualité viendra au palier 4 avec un dataset élargi.

CONFIGURATION
-------------
- Modèle  : mistralai/Mistral-Nemo-Base-2407 (12 B paramètres)
- Quantization : 4-bit NF4 + double quant, compute_dtype bf16
- LoRA   : r=8, lora_alpha=16, target_modules=[q_proj, v_proj], dropout=0.05
- Training : 3 epochs, batch_size=1 × grad_accum=4, lr=2e-4, warmup=10
- GPU    : A100-40GB (3,10 $/h chez Modal)

COÛT ATTENDU
------------
≈ 1,30 à 1,80 $ pour ≈ 20-30 min de training + chargement modèle.
Le `timeout=3600` plafonne dur à 1 h en cas de runaway.

RISQUES CONNUS
--------------
- OOM possible si batch_size ou seq_length augmenté
- Versions bitsandbytes / torch peuvent avoir des incompatibilités
  (les versions sont strictement pinées ci-dessous)
- Mistral Nemo Base n'a pas de chat_template par défaut — on en
  applique un (standard Mistral [INST]…[/INST]) avant training

PRÉREQUIS
---------
- Volume `mots-fleches-models` avec /Mistral-Nemo-Base-2407/ (palier 2)
- Volume `mots-fleches-datasets` avec /datasets/{train,val}.jsonl
  (palier 3a — fused par défaut, ou gold-only en mode smoke)

COMMANDE POUR LANCER
--------------------
    modal run modal_jobs/03b_finetune.py
"""

import modal


# ============================================================
# CONFIG ISSUE DU BENCHMARK A/B/C (palier 3b)
# ============================================================
# Résultats benchmark sur Mistral Nemo 12B (A100-40GB, 100 train +
# 14 val) :
#
#   Run A — batch=1, accum=4, no packing, flash-attn 2 :
#     runtime 174 s, 75 steps, eval_loss final 1.75
#     → flash-attn seul n'apporte rien à batch=1 (overhead par step
#       domine le compute attention)
#
#   Run B — batch=8, accum=1, no packing, flash-attn 2 :
#     runtime 32 s, 39 steps, eval_loss final 2.10
#     → x5 perf vs Run A grâce à l'utilisation GPU réelle, mais
#       moins d'updates (39 vs 75) → convergence légèrement dégradée
#
#   Run C — batch=8, accum=1, packing=True, flash-attn 2 :
#     runtime 13 s, 9 steps, eval_loss final 4.47
#     → packing concatène les exemples → seulement 9 updates sur tout
#       le training → modèle non convergé (loss > baseline)
#
# CONFIG FINALE RETENUE — meilleur rapport perf/qualité sur datasets
# 100-1000 exemples :
#   - batch=8 (Run B) : utilisation GPU réelle, ~10 samples/sec
#   - packing=False : préserve le nombre d'updates pour la convergence
#   - epochs=5 (vs 3) + warmup=15 : compense le moins d'updates par
#     epoch dû à batch=8 (39 updates × 5/3 ≈ 65, proche du baseline 75)
#   - max_seq=128 : marge x4 sur nos prompts ~30 tokens
#   - flash_attention_2 : prêt pour scale-up futur (batch >= 4 où
#     l'attention redevient le goulot)
#   - gradient_checkpointing=False : ~12 Go sur 40, pas besoin de
#     troquer vitesse contre mémoire
# ============================================================

# Hyperparamètres training (config finale issue du benchmark A/B/C)
BATCH_SIZE = 8
GRAD_ACCUM = 1
USE_PACKING = False
NUM_EPOCHS = 5
WARMUP_STEPS = 15
LEARNING_RATE = 2e-4
MAX_SEQ_LENGTH = 128


# Nom de l'app Modal
app = modal.App("mots-fleches-finetune")

# Volume modèles (lecture seule logique pour ce palier)
volume_models = modal.Volume.from_name(
    "mots-fleches-models", create_if_missing=False,
)
# Volume datasets (lecture seule logique pour ce palier)
volume_datasets = modal.Volume.from_name(
    "mots-fleches-datasets", create_if_missing=False,
)
# Volume adapters (création si manquant, écriture pour ce palier)
volume_adapters = modal.Volume.from_name(
    "mots-fleches-adapters", create_if_missing=True,
)

# Image Docker : python:3.11-slim + stack ML pinée strictement
# Les versions sont compatibles entre elles et testées avec
# Mistral Nemo 4-bit sur CUDA 12.4.
image = (
    modal.Image.from_registry("python:3.11-slim")
    .pip_install(
        "torch==2.5.0",
        "transformers==4.45.2",
        "peft==0.13.2",
        "bitsandbytes==0.44.1",
        "accelerate==1.0.1",
        "trl==0.11.4",
        "datasets==3.0.1",
        "sentencepiece",
        # trl 0.11.4 importe rich dans trainer/utils.py sans le déclarer
        # comme dépendance — bug de packaging, on l'ajoute explicitement.
        "rich",
    )
    # === flash-attn (wheel pré-compilé pour notre env exact) ===
    # On utilise directement le wheel publié sur GitHub releases qui
    # matche torch 2.5 + CUDA 12.4 + Python 3.11 + Linux x86_64.
    # Avantages vs install standard :
    # - Pas besoin de nvcc / CUDA toolkit pour builder depuis source
    # - Pas de compilation = setup rapide (~30 s au lieu de ~10 min)
    # Si l'URL ne match pas l'env, l'install échouera mais le code
    # Python a déjà le fallback try/except sur attn_implementation.
    .pip_install(
        # Tag CUDA dans le nom du wheel = "cu12" (pas "cu124"). C'est la
        # convention flash-attn : 1 wheel par MAJOR de CUDA, compatible
        # avec toutes les minor 12.x. URL HEAD vérifiée HTTP 200.
        "https://github.com/Dao-AILab/flash-attention/releases/download/"
        "v2.7.4.post1/"
        "flash_attn-2.7.4.post1+cu12torch2.5cxx11abiFALSE-"
        "cp311-cp311-linux_x86_64.whl"
    )
)


@app.function(
    image=image,
    gpu="A100-40GB",
    timeout=3600,
    volumes={
        "/models": volume_models,
        "/datasets": volume_datasets,
        "/adapters": volume_adapters,
    },
    secrets=[modal.Secret.from_name("huggingface")],
)
def finetune_pilot() -> dict:
    """Exécute le fine-tune QLoRA complet et retourne un récap."""
    # Imports lourds — DANS la fonction, sinon Modal essaierait de les
    # importer au build local
    import re

    import torch

    # === Désactivation du backend cuDNN SDPA ===
    # PyTorch 2.5 + cuDNN frontend a un bug avec l'attention Mistral
    # (GQA + sliding window) : le frontend cuDNN ne trouve pas d'exec
    # plan et lève "No execution plans support the graph". On désactive
    # uniquement le backend cuDNN — les backends flash / mem_efficient
    # / math restent disponibles et prendront le relais automatiquement.
    torch.backends.cuda.enable_cudnn_sdp(False)
    print("[INFO] torch.backends.cuda.enable_cudnn_sdp(False) — "
          "contournement bug cuDNN/Mistral")

    # ============================================================
    # DIAGNOSTIC BACKEND SDPA
    # ============================================================
    # On loggue l'état des backends SDPA après désactivation cuDNN,
    # plus un test fonctionnel pour vérifier que Flash/MemEfficient
    # marchent vraiment sur notre env (sinon fallback Math = lent).
    print("\n" + "=" * 60)
    print("DIAGNOSTIC BACKEND SDPA")
    print("=" * 60)
    print(f"torch version        : {torch.__version__}")
    print(f"CUDA build           : {torch.version.cuda}")
    print(f"cuDNN version        : {torch.backends.cudnn.version()}")
    print(f"cuDNN SDPA enabled   : "
          f"{torch.backends.cuda.cudnn_sdp_enabled()}")
    print(f"Flash SDPA enabled   : "
          f"{torch.backends.cuda.flash_sdp_enabled()}")
    print(f"Mem-Eff SDPA enabled : "
          f"{torch.backends.cuda.mem_efficient_sdp_enabled()}")
    print(f"Math SDPA enabled    : "
          f"{torch.backends.cuda.math_sdp_enabled()}")

    # Détection package flash_attn
    try:
        import flash_attn  # type: ignore
        flash_attn_available = True
        print(f"flash_attn package   : {flash_attn.__version__} "
              "(DISPONIBLE)")
    except ImportError:
        flash_attn_available = False
        print("flash_attn package   : NON INSTALLÉ "
              "(fallback SDPA prévu)")

    # Test fonctionnel : SDPA en n'autorisant que Flash + MemEfficient.
    # Si ça plante, c'est que seul Math (lent) est utilisable.
    print("\n[TEST] SDPA backend selection sur petit tensor "
          "(1×32×128×128, bf16)...")
    import torch.nn.functional as F
    q = torch.randn(1, 32, 128, 128, device="cuda",
                    dtype=torch.bfloat16)
    k = torch.randn(1, 32, 128, 128, device="cuda",
                    dtype=torch.bfloat16)
    v = torch.randn(1, 32, 128, 128, device="cuda",
                    dtype=torch.bfloat16)

    sdpa_fast_works = False
    try:
        with torch.backends.cuda.sdp_kernel(
            enable_flash=True,
            enable_math=False,
            enable_mem_efficient=True,
        ):
            _ = F.scaled_dot_product_attention(q, k, v, is_causal=True)
        sdpa_fast_works = True
        print("[TEST] Flash ou MemEfficient marche ✓")
    except Exception as exc:
        print(f"[TEST] Flash/MemEfficient échoue : {exc}")
        print("[TEST] Fallback Math sera utilisé (lent)")
    print()

    from transformers import (
        AutoModelForCausalLM,
        AutoTokenizer,
        BitsAndBytesConfig,
    )
    from peft import (
        LoraConfig,
        get_peft_model,
        prepare_model_for_kbit_training,
    )
    from datasets import load_dataset
    from trl import SFTConfig, SFTTrainer

    # ============================================================
    # CONFIGURATION TRAINING
    # ============================================================
    print("\n" + "=" * 60)
    print("CONFIGURATION TRAINING")
    print("=" * 60)
    print(f"batch_size             : {BATCH_SIZE}")
    print(f"grad_accumulation      : {GRAD_ACCUM}")
    print(f"packing                : {USE_PACKING}")
    print(f"num_epochs             : {NUM_EPOCHS}")
    print(f"warmup_steps           : {WARMUP_STEPS}")
    print(f"learning_rate          : {LEARNING_RATE}")
    print(f"max_seq_length         : {MAX_SEQ_LENGTH}")
    print(f"gradient_checkpointing : False")
    print(f"num_workers            : 2")
    print("=" * 60)

    # === Versions installées (debug ultérieur facilité) ===
    import transformers, peft, bitsandbytes, accelerate, trl, datasets
    print("=" * 60)
    print("VERSIONS INSTALLÉES")
    print("=" * 60)
    print(f"torch         : {torch.__version__}")
    print(f"transformers  : {transformers.__version__}")
    print(f"peft          : {peft.__version__}")
    print(f"bitsandbytes  : {bitsandbytes.__version__}")
    print(f"accelerate    : {accelerate.__version__}")
    print(f"trl           : {trl.__version__}")
    print(f"datasets      : {datasets.__version__}")

    # === GPU ===
    print("\n" + "=" * 60)
    print("GPU")
    print("=" * 60)
    print(f"cuda disponible : {torch.cuda.is_available()}")
    if torch.cuda.is_available():
        print(f"GPU              : {torch.cuda.get_device_name(0)}")
        props = torch.cuda.get_device_properties(0)
        print(f"Mémoire totale   : "
              f"{props.total_memory / (1024 ** 3):.2f} Go")

    # ============================================================
    # ÉTAPE A — Chargement du modèle Mistral Nemo Base en 4-bit
    # ============================================================
    print("\n" + "=" * 60)
    print("ÉTAPE A — Chargement Mistral Nemo Base 2407 (4-bit NF4)")
    print("=" * 60)

    bnb_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_quant_type="nf4",
        bnb_4bit_compute_dtype=torch.bfloat16,
        bnb_4bit_use_double_quant=True,
    )

    tokenizer = AutoTokenizer.from_pretrained(
        "/models/Mistral-Nemo-Base-2407",
    )
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
        print("[INFO] pad_token = eos_token (n'était pas défini)")

    # Flash Attention 2 + Mistral exige padding_side="left", sinon le
    # check dans Mistral._update_causal_mask (transformers 4.45) lève
    # une ValueError. On le force ici pour rester compatible aussi bien
    # avec le mode FA2 que SDPA (les deux tolèrent left padding).
    tokenizer.padding_side = "left"
    print("[INFO] tokenizer.padding_side = 'left' (compat FA2 + Mistral)")

    # Le modèle BASE n'a pas de chat_template — on en applique un
    # (standard Mistral [INST]...[/INST]) pour que SFTTrainer puisse
    # appliquer apply_chat_template sur le champ `messages`.
    if tokenizer.chat_template is None:
        print("[INFO] chat_template absent, application du template "
              "Mistral standard ([INST]...[/INST])")
        tokenizer.chat_template = (
            "{% for message in messages %}"
            "{% if message['role'] == 'user' %}"
            "[INST] {{ message['content'] }} [/INST]"
            "{% elif message['role'] == 'assistant' %}"
            " {{ message['content'] }}{{ eos_token }}"
            "{% endif %}"
            "{% endfor %}"
        )

    # Tentative Flash Attention 2 avec fallback SDPA. Si flash-attn
    # n'est pas installé ou si l'init flash_attention_2 plante, on
    # bascule sur attn_implementation="sdpa" (les backends Flash/
    # MemEfficient/Math sont alors choisis dynamiquement par torch).
    try:
        model = AutoModelForCausalLM.from_pretrained(
            "/models/Mistral-Nemo-Base-2407",
            quantization_config=bnb_config,
            device_map="auto",
            torch_dtype=torch.bfloat16,
            attn_implementation="flash_attention_2",
        )
        print("[OPT] Utilisation Flash Attention 2 ✓")
    except Exception as exc:
        print(f"[OPT] Flash Attention 2 indispo ({type(exc).__name__}: "
              f"{exc})")
        print("[OPT] Fallback sur attn_implementation='sdpa'")
        model = AutoModelForCausalLM.from_pretrained(
            "/models/Mistral-Nemo-Base-2407",
            quantization_config=bnb_config,
            device_map="auto",
            torch_dtype=torch.bfloat16,
            attn_implementation="sdpa",
        )

    mem_gb = torch.cuda.memory_allocated() / (1024 ** 3)
    print(f"Mémoire GPU post-chargement : {mem_gb:.2f} Go")

    # ============================================================
    # ÉTAPE B — Configuration LoRA
    # ============================================================
    print("\n" + "=" * 60)
    print("ÉTAPE B — Configuration LoRA")
    print("=" * 60)

    model = prepare_model_for_kbit_training(model)

    lora_config = LoraConfig(
        r=8,
        lora_alpha=16,
        target_modules=["q_proj", "v_proj"],
        lora_dropout=0.05,
        bias="none",
        task_type="CAUSAL_LM",
    )
    model = get_peft_model(model, lora_config)
    model.print_trainable_parameters()

    # ============================================================
    # ÉTAPE C — Chargement datasets train + val
    # ============================================================
    print("\n" + "=" * 60)
    print("ÉTAPE C — Datasets")
    print("=" * 60)

    dataset = load_dataset(
        "json",
        data_files={
            "train": "/datasets/train.jsonl",
            "validation": "/datasets/val.jsonl",
        },
    )
    print(f"train      : {len(dataset['train'])} exemples")
    print(f"validation : {len(dataset['validation'])} exemples")
    print(f"Exemple train[0] : {dataset['train'][0]}")

    # ============================================================
    # ÉTAPE D — Training avec SFTTrainer
    # ============================================================
    print("\n" + "=" * 60)
    print("ÉTAPE D — Training SFT (3 epochs)")
    print("=" * 60)

    sft_config = SFTConfig(
        output_dir="/tmp/training_output",
        # Hyperparams issus du benchmark A/B/C (cf. tête du fichier)
        num_train_epochs=NUM_EPOCHS,
        per_device_train_batch_size=BATCH_SIZE,
        gradient_accumulation_steps=GRAD_ACCUM,
        packing=USE_PACKING,
        learning_rate=LEARNING_RATE,
        warmup_steps=WARMUP_STEPS,
        max_seq_length=MAX_SEQ_LENGTH,
        logging_steps=5,
        eval_strategy="epoch",
        save_strategy="epoch",
        save_total_limit=1,
        bf16=True,
        # Pas besoin avec batch petit + 4-bit (~12 Go sur 40 dispo)
        gradient_checkpointing=False,
        # Préfetch parallélisé
        dataloader_num_workers=2,
        report_to="none",
    )

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        args=sft_config,
        train_dataset=dataset["train"],
        eval_dataset=dataset["validation"],
    )

    # Reset des stats peak memory pour avoir une mesure propre du
    # pic durant le training (hors chargement modèle).
    torch.cuda.reset_peak_memory_stats()

    try:
        train_result = trainer.train()
    except torch.cuda.OutOfMemoryError as oom:
        print(f"\n[OOM] Out of Memory pendant le training : {oom}")
        print(f"[OOM] batch_size={BATCH_SIZE}  grad_accum={GRAD_ACCUM}  "
              f"packing={USE_PACKING}  max_seq={MAX_SEQ_LENGTH}")
        print(f"[OOM] Pic mémoire avant crash : "
              f"{torch.cuda.max_memory_allocated() / (1024 ** 3):.2f} Go")
        print(f"[OOM] Suggestions :")
        print(f"  - Réduire batch_size (essayer 4 ou 2)")
        print(f"  - Réduire max_seq_length")
        print(f"  - Réactiver gradient_checkpointing=True")
        raise

    print(f"\nTraining terminé. Loss moyenne (train) : "
          f"{train_result.training_loss:.4f}")

    # Extraire les eval losses depuis l'historique des logs.
    # IMPORTANT : doit être fait AVANT result_perf qui les référence.
    eval_losses: list[float] = []
    for log in trainer.state.log_history:
        if "eval_loss" in log:
            eval_losses.append(float(log["eval_loss"]))
    print(f"Eval losses (par epoch) : "
          f"{[f'{x:.4f}' for x in eval_losses]}")

    # === Mesure perf structurée ===
    runtime = float(train_result.metrics.get("train_runtime", 0.0))
    steps_total = int(train_result.global_step)
    steps_per_sec = steps_total / runtime if runtime > 0 else 0.0
    # samples_per_sec = batch effectif × steps/sec
    samples_per_sec = (
        (steps_total * BATCH_SIZE * GRAD_ACCUM) / runtime
        if runtime > 0 else 0.0
    )
    gpu_peak_gb = torch.cuda.max_memory_allocated() / (1024 ** 3)

    result_perf = {
        "batch_size": BATCH_SIZE,
        "grad_accum": GRAD_ACCUM,
        "packing": USE_PACKING,
        "num_epochs": NUM_EPOCHS,
        "train_runtime_s": round(runtime, 2),
        "total_steps": steps_total,
        "steps_per_sec": round(steps_per_sec, 4),
        "samples_per_sec": round(samples_per_sec, 4),
        "train_loss_final": round(float(train_result.training_loss), 4),
        "eval_losses": [round(x, 4) for x in eval_losses],
        "gpu_peak_memory_gb": round(gpu_peak_gb, 2),
    }

    print()
    print("=" * 60)
    print("PERFORMANCE TRAINING")
    print("=" * 60)
    for key, value in result_perf.items():
        print(f"  {key:25s} : {value}")

    # ============================================================
    # ÉTAPE E — Sauvegarde adapters
    # ============================================================
    print("\n" + "=" * 60)
    print("ÉTAPE E — Sauvegarde adapters LoRA")
    print("=" * 60)

    # mistral-nemo-pilot-v1 = première itération sur le corpus fusé
    # (PR 4b). Le namespace ``mistral-nemo-pilot-vN`` est déclaré au
    # spec §3.3.
    adapter_path = "/adapters/mistral-nemo-pilot-v1"
    model.save_pretrained(adapter_path)
    tokenizer.save_pretrained(adapter_path)
    volume_adapters.commit()
    print(f"Adapters sauvegardés dans {adapter_path}")
    print(f"Volume `mots-fleches-adapters` committé.")

    # ============================================================
    # ÉTAPE F — Évaluation visuelle : 5 mots du val
    # ============================================================
    print("\n" + "=" * 60)
    print("ÉTAPE F — Évaluation visuelle (5 exemples du val)")
    print("=" * 60)

    # Bascule en mode inférence (désactive dropout etc.) ; équivalent
    # de model.eval() en API PyTorch standard.
    model.train(False)
    val_examples = dataset["validation"].select(range(5))
    examples_out: list[dict] = []

    for ex in val_examples:
        user_msg = ex["messages"][0]["content"]
        gold_def = ex["messages"][1]["content"]

        # Extraire le mot depuis le prompt "...pour MOT."
        match = re.search(r"pour (\S+)\.", user_msg)
        mot = match.group(1) if match else "?"

        # Tokeniser avec apply_chat_template + add_generation_prompt=True
        prompt_messages = [{"role": "user", "content": user_msg}]
        inputs = tokenizer.apply_chat_template(
            prompt_messages,
            add_generation_prompt=True,
            return_tensors="pt",
        ).to(model.device)

        # Génération greedy (do_sample=False pour reproductibilité)
        with torch.no_grad():
            outputs = model.generate(
                inputs,
                max_new_tokens=30,
                do_sample=False,
                pad_token_id=tokenizer.eos_token_id,
            )
        generated = tokenizer.decode(
            outputs[0][inputs.shape[1]:],
            skip_special_tokens=True,
        ).strip()

        print(f"  {mot}")
        print(f"    Gold      : {gold_def}")
        print(f"    Généré    : {generated}")
        examples_out.append({
            "mot": str(mot),
            "gold": str(gold_def),
            "generated": str(generated),
        })

    return {
        "status": "completed",
        "training_loss_finale": float(train_result.training_loss),
        "eval_losses": eval_losses,
        "adapter_path": adapter_path,
        "examples": examples_out,
        "perf_result": result_perf,
    }


@app.local_entrypoint()
def main() -> None:
    """Lance le fine-tune et affiche le récap final."""
    print("[LOCAL] Lancement fine-tune Mistral Nemo (palier 3b)...")
    print("[LOCAL] /!\\ Durée attendue ≈ 20-30 min "
          "(chargement + 3 epochs + sauvegarde + génération)")
    print("[LOCAL] /!\\ Coût attendu ≈ 1,30 à 1,80 $ "
          "(A100-40GB à 3,10 $/h)")
    print()

    resultat = finetune_pilot.remote()

    print()
    print("=" * 60)
    print("RÉCAP FINAL (côté local)")
    print("=" * 60)
    print(f"Status                  : {resultat['status']}")
    print(f"Training loss finale    : "
          f"{resultat['training_loss_finale']:.4f}")
    print(f"Eval losses par epoch   : "
          f"{[f'{x:.4f}' for x in resultat['eval_losses']]}")
    print(f"Adapters sauvegardés à  : {resultat['adapter_path']}")
    print()

    # Récap perf condensé
    perf = resultat["perf_result"]
    print("=" * 60)
    print("PERFORMANCE RECAP")
    print("=" * 60)
    for key, value in perf.items():
        print(f"  {key:25s} : {value}")
    print()
    print("5 exemples génération vs gold :")
    for ex in resultat["examples"]:
        print(f"  {ex['mot']}")
        print(f"    Gold      : {ex['gold']}")
        print(f"    Généré    : {ex['generated']}")
    print()
    print("Palier 3b validé : infrastructure de fine-tune QLoRA "
          "opérationnelle.")
    print("Les adapters sont disponibles sur le volume "
          "`mots-fleches-adapters` pour inférence ultérieure.")
