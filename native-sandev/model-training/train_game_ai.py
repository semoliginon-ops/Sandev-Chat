#!/usr/bin/env python3
"""Fine-tune a small instruction model on synthetic, rule-labelled game decisions."""
import json
import os
import random
from pathlib import Path

import torch
from torch.utils.data import Dataset
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    DataCollatorForSeq2Seq,
    Trainer,
    TrainingArguments,
)

BASE_MODEL = os.environ.get("BASE_MODEL", "HuggingFaceTB/SmolLM2-135M-Instruct")
OUT_DIR = Path(os.environ.get("MODEL_OUT", "artifacts/sandev-game-ai-hf"))
SEED = 20261024
random.seed(SEED)
torch.manual_seed(SEED)

GENRES = [
    "turn-based RPG", "tactical strategy", "survival crafting",
    "puzzle adventure", "racing", "platformer", "tower defense",
    "stealth", "resource management", "card battler",
]

def clamp(x):
    return max(0.0, min(1.0, x))

def score_action(a, state):
    # Transparent teacher policy: labels reflect survival, progress, cost, risk,
    # information gain, and objective urgency; game code still enforces legality.
    health = state["health"]
    resources = state["resources"]
    uncertainty = state["uncertainty"]
    urgency = state["objective_urgency"]
    return (
        a["progress"] * (0.8 + urgency * 1.5)
        + a["survival"] * (0.5 + (1.0 - health) * 2.0)
        + a["information_gain"] * (0.2 + uncertainty)
        + a["objective_fit"] * (0.4 + urgency)
        - a["resource_cost"] * (0.3 + (1.0 - resources) * 1.2)
        - a["risk"] * (0.5 + (1.0 - health) * 1.5)
    )

def make_example():
    genre = random.choice(GENRES)
    state = {
        "health": round(random.random(), 2),
        "resources": round(random.random(), 2),
        "threat": round(random.random(), 2),
        "uncertainty": round(random.random(), 2),
        "objective_urgency": round(random.random(), 2),
        "turn": random.randint(1, 40),
    }
    count = random.randint(3, 5)
    candidates = []
    used = set()
    for _ in range(count):
        while True:
            action_id = f"act_{random.randint(10, 999)}"
            if action_id not in used:
                used.add(action_id)
                break
        candidates.append({
            "id": action_id,
            "progress": round(random.random(), 2),
            "survival": round(random.random(), 2),
            "information_gain": round(random.random(), 2),
            "objective_fit": round(random.random(), 2),
            "resource_cost": round(random.random(), 2),
            "risk": round(random.random(), 2),
        })
    random.shuffle(candidates)
    best = max(candidates, key=lambda a: score_action(a, state))
    user = (
        "Choose the best legal action for this game state. The action feature values "
        "are normalized from 0 to 1. Prioritize survival when health is low, progress "
        "when the objective is urgent, information when uncertainty is high, and "
        "conserve resources when resources are low. Return exactly one line: ACTION: <id>.\n"
        f"Game genre: {genre}\n"
        f"State: {json.dumps(state, separators=(',', ':'))}\n"
        f"Legal actions: {json.dumps(candidates, separators=(',', ':'))}"
    )
    return user, f"ACTION: {best['id']}"

class GameDecisionDataset(Dataset):
    def __init__(self, tokenizer, size=600, max_length=256):
        self.rows = []
        for _ in range(size):
            user_text, answer = make_example()
            prompt = tokenizer.apply_chat_template(
                [
                    {"role": "system", "content": "You are SANDEV Game AI. Select only from supplied legal actions. Never invent an action ID."},
                    {"role": "user", "content": user_text},
                ],
                tokenize=False,
                add_generation_prompt=True,
            )
            prompt_ids = tokenizer(prompt, add_special_tokens=False)["input_ids"]
            answer_ids = tokenizer(answer + tokenizer.eos_token, add_special_tokens=False)["input_ids"]
            # Preserve the answer and keep the most recent prompt context if needed.
            if len(prompt_ids) + len(answer_ids) > max_length:
                prompt_ids = prompt_ids[-(max_length - len(answer_ids)):]
            ids = prompt_ids + answer_ids
            labels = ([-100] * len(prompt_ids)) + answer_ids
            self.rows.append({
                "input_ids": ids,
                "attention_mask": [1] * len(ids),
                "labels": labels,
            })

    def __len__(self):
        return len(self.rows)

    def __getitem__(self, index):
        return self.rows[index]

def main():
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Loading base checkpoint: {BASE_MODEL}", flush=True)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    if tokenizer.pad_token is None:
        tokenizer.pad_token = tokenizer.eos_token
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL)
    dataset = GameDecisionDataset(tokenizer)
    collator = DataCollatorForSeq2Seq(
        tokenizer=tokenizer,
        padding=True,
        label_pad_token_id=-100,
        return_tensors="pt",
    )
    args = TrainingArguments(
        output_dir=str(OUT_DIR / "training-checkpoints"),
        num_train_epochs=1,
        per_device_train_batch_size=4,
        gradient_accumulation_steps=2,
        learning_rate=2e-5,
        warmup_steps=5,
        logging_steps=20,
        save_strategy="no",
        report_to=[],
        dataloader_num_workers=0,
        remove_unused_columns=False,
        seed=SEED,
    )
    trainer = Trainer(
        model=model,
        args=args,
        train_dataset=dataset,
        data_collator=collator,
        tokenizer=tokenizer,
    )
    result = trainer.train()
    model.save_pretrained(OUT_DIR, safe_serialization=True)
    tokenizer.save_pretrained(OUT_DIR)
    report = {
        "model_name": "SANDEV Game AI Alpha",
        "base_checkpoint": BASE_MODEL,
        "method": "full fine-tuning on synthetic rule-labelled game decision examples",
        "training_examples": len(dataset),
        "seed": SEED,
        "train_loss": result.metrics.get("train_loss"),
        "genres_in_synthetic_data": GENRES,
        "limitations": [
            "Prototype, not trained from random initialization.",
            "Synthetic labels teach a transparent heuristic, not expert game play.",
            "Must be benchmarked on unseen game states and on-device before production use.",
        ],
    }
    (OUT_DIR / "training-report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2), flush=True)

if __name__ == "__main__":
    main()
