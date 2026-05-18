#!/usr/bin/env python3
"""
Vosk vs Whisper accuracy spike for short watch commands.

Runs every .wav in `--audio-dir` through Vosk three ways:
  1. Default vocabulary (general English)
  2. Grammar-constrained against a verb × contact list
  3. (Reference) the Whisper transcript already on disk, if a sidecar
     .txt file with the same basename exists.

Prints a comparison table so we can decide whether to ship a
:speech-vosk module alongside :speech-whisper.

The audio files come from `ConversationAudioStore` on the dev phone
(`/data/data/com.lazydevs.wristotle/files/conversation-audio/`). Pull
them with:

  adb shell run-as com.lazydevs.wristotle ls files/conversation-audio
  for f in $(adb shell run-as com.lazydevs.wristotle ls files/conversation-audio); do
      adb shell run-as com.lazydevs.wristotle cat files/conversation-audio/$f \
          > /tmp/wristotle-spike/$f
  done

Then create a sidecar .csv at /tmp/wristotle-spike/expected.csv with
the intended command for each clip (so we can score correctness):

  filename,expected_text,intent
  1779001234567-48000.wav,call dad,Call
  1779001235000-72000.wav,text mom saying running late,Sms

Optionally drop a .txt next to each .wav with the same basename
containing what Whisper transcribed; the script will include it in
the comparison table.

Usage:

  uv tool install vosk  # or: pip install vosk
  curl -LO https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip
  unzip vosk-model-small-en-us-0.15.zip -d ~/vosk-models/
  python3 tools/vosk_spike.py \\
      --model ~/vosk-models/vosk-model-small-en-us-0.15 \\
      --audio-dir /tmp/wristotle-spike \\
      --contacts contacts.txt

`contacts.txt` is one contact name per line — your actual address-book
names so we test the real failure modes.
"""

import argparse
import csv
import json
import os
import re
import sys
import time
import wave
from pathlib import Path
from typing import Optional

try:
    from vosk import Model, KaldiRecognizer, SetLogLevel
except ImportError:
    sys.exit("vosk not installed — run: pip install vosk  (or: uv pip install vosk)")

SetLogLevel(-1)  # silence vosk's stderr chatter

# ── Grammar construction ─────────────────────────────────────────────────────

VERBS = {
    "Call":      ["call {c}", "dial {c}", "ring {c}", "phone {c}"],
    "Sms":       ["text {c}", "message {c}", "send a message to {c}", "tell {c}"],
    "Reminder":  ["remind me", "reminder", "set a reminder"],
    "Cancel":    ["cancel", "cancel reminder", "delete reminder"],
    "FindPhone": ["find my phone", "where is my phone", "where's my phone"],
}

def build_grammar(contacts: list[str]) -> str:
    """JSON grammar for Vosk — list of allowed phrases. `[unk]` is the
    Vosk-conventional fallback marker so the recognizer won't choke on
    unexpected words; it'll emit them but they won't bias decoding."""
    phrases: set[str] = set()
    for templates in VERBS.values():
        for tpl in templates:
            if "{c}" in tpl:
                for c in contacts:
                    phrases.add(tpl.format(c=c.lower()))
            else:
                phrases.add(tpl)
    phrases.add("[unk]")
    return json.dumps(sorted(phrases))

# ── Audio + recognition ──────────────────────────────────────────────────────

def transcribe(path: Path, model: Model, grammar: Optional[str] = None) -> tuple[str, float]:
    """Run Vosk over a 16 kHz / mono / PCM-16 .wav. Returns (text, seconds)."""
    with wave.open(str(path), "rb") as wf:
        if wf.getframerate() != 16000 or wf.getnchannels() != 1 or wf.getsampwidth() != 2:
            return (f"<bad format: {wf.getframerate()}Hz {wf.getnchannels()}ch {wf.getsampwidth()*8}bit>", 0.0)
        rec = KaldiRecognizer(model, 16000.0, grammar) if grammar else KaldiRecognizer(model, 16000.0)
        rec.SetWords(False)
        t0 = time.perf_counter()
        while True:
            buf = wf.readframes(4000)
            if not buf:
                break
            rec.AcceptWaveform(buf)
        result = json.loads(rec.FinalResult())
        return (result.get("text", "").strip(), time.perf_counter() - t0)

# ── Scoring ──────────────────────────────────────────────────────────────────

_word_re = re.compile(r"[a-z0-9]+")

def normalise(s: str) -> list[str]:
    return _word_re.findall(s.lower())

def exact_match(expected: str, actual: str) -> bool:
    return normalise(expected) == normalise(actual)

def contains_match(expected_words: list[str], actual: str) -> bool:
    """Lenient — every word in the expected text appears (in order, but
    not necessarily contiguous) in the actual transcript. Lets a
    transcript like "yes call dad please" still match expected "call
    dad" without forcing a literal-equal."""
    actual_words = normalise(actual)
    i = 0
    for w in expected_words:
        while i < len(actual_words) and actual_words[i] != w:
            i += 1
        if i >= len(actual_words):
            return False
        i += 1
    return True

# ── Main ─────────────────────────────────────────────────────────────────────

def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, type=Path, help="Path to unzipped Vosk model dir")
    ap.add_argument("--audio-dir", required=True, type=Path,
                    help="Dir containing .wav files (16k mono PCM-16) + optional expected.csv + per-file .txt sidecars")
    ap.add_argument("--contacts", type=Path,
                    help="Optional: one contact name per line, for building the grammar")
    args = ap.parse_args()

    if not args.model.is_dir():
        sys.exit(f"model dir not found: {args.model}")
    if not args.audio_dir.is_dir():
        sys.exit(f"audio dir not found: {args.audio_dir}")

    contacts: list[str] = []
    if args.contacts and args.contacts.exists():
        contacts = [line.strip() for line in args.contacts.read_text().splitlines() if line.strip()]
    if not contacts:
        contacts = ["mom", "dad", "sister"]
        print(f"!! no --contacts file given; using placeholder list: {contacts}")
    grammar = build_grammar(contacts)
    print(f"grammar covers {grammar.count(',') + 1} phrases across {len(contacts)} contacts\n")

    expected_csv = args.audio_dir / "expected.csv"
    expected: dict[str, dict[str, str]] = {}
    if expected_csv.exists():
        with expected_csv.open() as f:
            for row in csv.DictReader(f):
                expected[row["filename"]] = row

    print("loading vosk model...", flush=True)
    t_load = time.perf_counter()
    model = Model(str(args.model))
    print(f"  loaded in {time.perf_counter() - t_load:.2f}s\n")

    rows: list[dict[str, str]] = []
    score = {"default_exact": 0, "grammar_exact": 0, "default_lenient": 0, "grammar_lenient": 0, "total": 0}

    for wav in sorted(args.audio_dir.glob("*.wav")):
        whisper_txt = ""
        sidecar = wav.with_suffix(".txt")
        if sidecar.exists():
            whisper_txt = sidecar.read_text().strip()
        meta = expected.get(wav.name, {})
        exp_text = meta.get("expected_text", "")
        intent   = meta.get("intent", "")

        default_text, default_ms = transcribe(wav, model)
        grammar_text, grammar_ms = transcribe(wav, model, grammar)

        if exp_text:
            score["total"] += 1
            exp_words = normalise(exp_text)
            if exact_match(exp_text, default_text):  score["default_exact"]  += 1
            if exact_match(exp_text, grammar_text):  score["grammar_exact"]  += 1
            if contains_match(exp_words, default_text): score["default_lenient"] += 1
            if contains_match(exp_words, grammar_text): score["grammar_lenient"] += 1

        rows.append({
            "file":       wav.name,
            "intent":     intent,
            "expected":   exp_text,
            "whisper":    whisper_txt,
            "vosk":       f"{default_text}  [{default_ms*1000:.0f}ms]",
            "vosk_gram":  f"{grammar_text}  [{grammar_ms*1000:.0f}ms]",
        })

    if not rows:
        sys.exit("no .wav files found in audio-dir")

    # Pretty-print
    headers = ["file", "intent", "expected", "whisper", "vosk", "vosk_gram"]
    widths = {h: max(len(h), *(len(r[h]) for r in rows)) for h in headers}
    line = " | ".join(h.ljust(widths[h]) for h in headers)
    print(line)
    print("-" * len(line))
    for r in rows:
        print(" | ".join(r[h].ljust(widths[h]) for h in headers))

    if score["total"]:
        print()
        print(f"scored against {score['total']} files with expected_text in expected.csv:")
        print(f"  vosk default,  exact:   {score['default_exact']}/{score['total']}")
        print(f"  vosk default,  lenient: {score['default_lenient']}/{score['total']}")
        print(f"  vosk grammar,  exact:   {score['grammar_exact']}/{score['total']}")
        print(f"  vosk grammar,  lenient: {score['grammar_lenient']}/{score['total']}")

    return 0

if __name__ == "__main__":
    sys.exit(main())
