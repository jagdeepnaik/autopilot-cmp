#!/usr/bin/env python3
"""Build the immutable, reviewed Q&A source database from private/qa inputs.

The source JSON and CSV files remain authoritative records.  This database stores their
full payloads as well as a normalized, indexed representation for app lookup.
"""
from __future__ import annotations

import csv
import hashlib
import json
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
QA = ROOT / "private" / "qa"
OUT = QA / "autopilot_qa.db"


def norm(value: str) -> str:
    return " ".join(value.replace("\u00a0", " ").split()).casefold()


def text_option(value: str) -> str:
    value = value.replace("\u00a0", " ").strip()
    # Match the app's display-prefix rule while retaining meaningful numeric values.
    import re
    return re.sub(r"^\d+[.)]\s+", "", value)


def digest(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def main() -> None:
    if OUT.exists():
        OUT.unlink()
    db = sqlite3.connect(OUT)
    db.execute("PRAGMA foreign_keys = ON")
    db.executescript(
        """
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE source_documents (
          name TEXT PRIMARY KEY, kind TEXT NOT NULL, sha256 TEXT NOT NULL,
          bytes INTEGER NOT NULL, imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
        );
        CREATE TABLE source_cbt (course_code TEXT PRIMARY KEY, course_name TEXT NOT NULL, course_version TEXT NOT NULL);
        CREATE TABLE qa_questions (
          id INTEGER PRIMARY KEY,
          routine TEXT NOT NULL,
          bank_name TEXT NOT NULL,
          module TEXT NOT NULL,
          question_text TEXT NOT NULL,
          question_normalized TEXT NOT NULL,
          source TEXT NOT NULL CHECK(source IN ('bundled_json','extracted_ces','learned')),
          source_reference TEXT,
          imported_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
          UNIQUE(routine, bank_name, module, question_normalized, source_reference)
        );
        CREATE TABLE qa_options (
          question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE,
          position INTEGER NOT NULL,
          option_text TEXT NOT NULL,
          option_normalized TEXT NOT NULL,
          is_correct INTEGER NOT NULL CHECK(is_correct IN (0,1)),
          PRIMARY KEY(question_id, position)
        );
        CREATE TABLE qa_answers (
          question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE,
          answer_text TEXT NOT NULL,
          answer_normalized TEXT NOT NULL,
          source TEXT NOT NULL CHECK(source IN ('bundled_json','extracted_ces','learned')),
          learned_at INTEGER,
          reviewed_at INTEGER,
          PRIMARY KEY(question_id, answer_normalized)
        );
        CREATE INDEX qa_question_lookup ON qa_questions(routine, bank_name, module, question_normalized);
        """
    )

    def add_question(routine: str, bank: str, module: str, question: str, options: list[str], answers: list[str],
                     source: str, reference: str | None, source_hash: str) -> None:
        clean_options = [text_option(value) for value in options]
        cursor = db.execute(
            "INSERT OR IGNORE INTO qa_questions(routine,bank_name,module,question_text,question_normalized,source,source_reference) VALUES (?,?,?,?,?,?,?)",
            (routine, bank, module, question, norm(question), source, reference),
        )
        if cursor.rowcount == 0:
            return
        question_id = cursor.lastrowid
        normalized_answers = {norm(text_option(answer)) for answer in answers if answer}
        for index, option in enumerate(clean_options, 1):
            db.execute(
                "INSERT INTO qa_options(question_id,position,option_text,option_normalized,is_correct) VALUES (?,?,?,?,?)",
                (question_id, index, option, norm(option), int(norm(option) in normalized_answers)),
            )
        for answer in answers:
            if answer:
                db.execute(
                    "INSERT INTO qa_answers(question_id,answer_text,answer_normalized,source) VALUES (?,?,?,?)",
                    (question_id, text_option(answer), norm(text_option(answer)), source),
                )

    json_files = sorted(path for path in QA.glob("*.json") if path.is_file())
    for path in json_files:
        payload = path.read_bytes()
        source_hash = digest(payload)
        db.execute("INSERT INTO source_documents(name,kind,sha256,bytes) VALUES (?,?,?,?)", (path.name, "json", source_hash, len(payload)))
        data = json.loads(payload)
        if path.name == "olp-apk-multi-answer-reference-ver-1.0.json":
            for module, entries in data.get("courses", {}).items():
                for position, entry in enumerate(entries, 1):
                    add_question("ocean-learning-platform", path.name, module, entry["question"], entry["options"], entry.get("answers", []),
                                 "bundled_json", f"{path.name}:{module}:{position}", source_hash)
            continue
        if not all(isinstance(items, list) and all(isinstance(item, dict) and "question" in item and "options" in item for item in items) for items in data.values()):
            continue
        routine = "ocean-learning-platform" if path.name.startswith("olp-") else "ocean-learning-platform" if path.name.startswith("oceantg-") else "sagar-mein-yog"
        for module, entries in data.items():
            for position, entry in enumerate(entries, 1):
                add_question(routine, path.name, module, entry["question"], entry["options"], [entry["answer"]] if entry.get("answer") else [], "bundled_json", f"{path.name}:{module}:{position}", source_hash)

    inspection = QA / "apk-inspection"
    inspection_hashes: dict[str, str] = {}
    for name in ("ces.csv", "cbt.csv", "questions.csv", "answers.csv"):
        payload = (inspection / name).read_bytes()
        inspection_hashes[name] = digest(payload)
        db.execute("INSERT INTO source_documents(name,kind,sha256,bytes) VALUES (?,?,?,?)", (f"apk-inspection/{name}", "csv", inspection_hashes[name], len(payload)))

    with (inspection / "ces.csv").open(newline="", encoding="utf-8-sig") as handle:
        ces_rows = list(csv.DictReader(handle))
    for row in ces_rows:
        options = [row[f"A{index}_TEXT"] for index in range(1, 5)]
        correct = [row[f"A{index}_TEXT"] for index in range(1, 5) if row[f"A{index}_CORR"] == "1"]
        add_question("seagull-ces", "ces_qa.db", "CES", row["Q_TEXT"], options, correct,
                     "extracted_ces", f"CES:{row['_id']}:{row['Q_NO']}", inspection_hashes["ces.csv"])

    cbt_by_code: dict[str, str] = {}
    with (inspection / "cbt.csv").open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            # This source export labels its first column `_id`, but it contains the CBT code.
            db.execute("INSERT INTO source_cbt VALUES (?,?,?)", (row["_id"], row["CBT_NAME"], row["CBT_VER"]))
            cbt_by_code[row["_id"]] = row["CBT_NAME"] + " — " + row["CBT_VER"]
    question_rows: dict[str, tuple[str, str]] = {}
    with (inspection / "questions.csv").open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            question_rows[row["_id"]] = (row["CD"], row["Q_TEXT"])
    answered_question_ids: set[str] = set()
    with (inspection / "answers.csv").open(newline="", encoding="utf-8-sig") as handle:
        for row in csv.DictReader(handle):
            source_question = question_rows.get(row["_id"])
            if source_question is None:
                continue
            answered_question_ids.add(row["_id"])
            options, correct = [], []
            for candidate in row["A_TEXT"].split("|"):
                if candidate and candidate[-1:] in {"0", "1"}:
                    value, marker = candidate[:-1], candidate[-1]
                    options.append(value)
                    if marker == "1":
                        correct.append(value)
            # Preserve every source question, including multi-answer and reference-only entries.
            code, question = source_question
            add_question("seagull-cbt", "ces_qa.db", cbt_by_code.get(code, code), question, options, correct, "extracted_ces",
                         f"QUESTIONS:{row['_id']}:{code}", inspection_hashes["questions.csv"] + ":" + inspection_hashes["answers.csv"])
    # A small number of question rows have no matching answer row. Keep them as reference-only Q&A records.
    for source_id, (code, question) in question_rows.items():
        if source_id not in answered_question_ids:
            add_question("seagull-cbt", "ces_qa.db", cbt_by_code.get(code, code), question, [], [], "extracted_ces",
                         f"QUESTIONS:{source_id}:{code}", inspection_hashes["questions.csv"])

    db.execute("INSERT INTO metadata VALUES (?,?)", ("format", "autopilot_qa.v1"))
    db.execute("INSERT INTO metadata VALUES (?,?)", ("generated_from", "private/qa JSON and apk-inspection CSV exports"))
    db.execute("INSERT INTO metadata VALUES (?,?)", ("source_document_count", str(len(json_files) + 4)))
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(OUT)


if __name__ == "__main__":
    main()
