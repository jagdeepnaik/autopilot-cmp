#!/usr/bin/env python3
"""Build the immutable, reviewed Q&A source database from private/qa inputs.

The source JSON and CSV files remain authoritative records.  This database stores their
full payloads as well as a normalized, indexed representation for app lookup.
"""
from __future__ import annotations

import csv
import hashlib
import json
import os
import shutil
import sqlite3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
QA = ROOT / "private" / "qa"
OUT = QA / "autopilot_qa.db"
RUNTIME_RESOURCES = ROOT / "shared" / "src" / "jvmMain" / "resources" / "qa-routines"


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
    compact_database(OUT)
    generated = split_routine_databases(OUT)
    OUT.unlink()
    print("Generated routine Q&A databases:")
    for path in generated:
        print(path)


def compact_database(raw_path: Path) -> None:
    """Normalize repeated question/option sets while retaining one source link per original record."""
    compact_path = raw_path.with_suffix(".compact.db")
    if compact_path.exists():
        compact_path.unlink()
    source = sqlite3.connect(raw_path)
    target = sqlite3.connect(compact_path)
    target.execute("PRAGMA foreign_keys = ON")
    target.executescript(
        """
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE source_documents (name TEXT PRIMARY KEY, kind TEXT NOT NULL, sha256 TEXT NOT NULL, bytes INTEGER NOT NULL, imported_at TEXT NOT NULL);
        CREATE TABLE source_cbt (course_code TEXT PRIMARY KEY, course_name TEXT NOT NULL, course_version TEXT NOT NULL);
        CREATE TABLE qa_questions (id INTEGER PRIMARY KEY, question_text TEXT NOT NULL, question_normalized TEXT NOT NULL);
        CREATE TABLE qa_options (question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE, position INTEGER NOT NULL, option_text TEXT NOT NULL, option_normalized TEXT NOT NULL, PRIMARY KEY(question_id, position));
        CREATE TABLE qa_question_sources (
          id INTEGER PRIMARY KEY, question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE,
          routine TEXT NOT NULL, bank_name TEXT NOT NULL, module TEXT NOT NULL, source TEXT NOT NULL,
          source_reference TEXT, imported_at TEXT NOT NULL
        );
        CREATE TABLE qa_source_answers (
          source_id INTEGER NOT NULL REFERENCES qa_question_sources(id) ON DELETE CASCADE,
          answer_text TEXT NOT NULL, answer_normalized TEXT NOT NULL,
          learned_at INTEGER, reviewed_at INTEGER, PRIMARY KEY(source_id, answer_normalized)
        );
        CREATE INDEX qa_question_lookup ON qa_questions(question_normalized);
        CREATE INDEX qa_source_bank_lookup ON qa_question_sources(bank_name, module, question_id);
        CREATE INDEX qa_source_question_lookup ON qa_question_sources(question_id, bank_name);
        """
    )
    target.executemany("INSERT INTO metadata VALUES (?,?)", source.execute("SELECT key,value FROM metadata"))
    target.executemany("INSERT INTO source_documents VALUES (?,?,?,?,?)", source.execute("SELECT name,kind,sha256,bytes,imported_at FROM source_documents"))
    target.executemany("INSERT INTO source_cbt VALUES (?,?,?)", source.execute("SELECT course_code,course_name,course_version FROM source_cbt"))
    question_ids: dict[tuple[str, tuple[str, ...]], int] = {}
    for row in source.execute("SELECT id,routine,bank_name,module,question_text,question_normalized,source,source_reference,imported_at FROM qa_questions ORDER BY id"):
        original_id, routine, bank, module, question, question_key, provenance, reference, imported = row
        options = list(source.execute("SELECT option_text,option_normalized FROM qa_options WHERE question_id=? ORDER BY position", (original_id,)))
        fingerprint = (question_key, tuple(sorted(option[1] for option in options)))
        question_id = question_ids.get(fingerprint)
        if question_id is None:
            cursor = target.execute("INSERT INTO qa_questions(question_text,question_normalized) VALUES (?,?)", (question, question_key))
            question_id = cursor.lastrowid
            question_ids[fingerprint] = question_id
            target.executemany("INSERT INTO qa_options(question_id,position,option_text,option_normalized) VALUES (?,?,?,?)",
                               ((question_id, position, option, option_key) for position, (option, option_key) in enumerate(options, 1)))
        source_id = target.execute("INSERT INTO qa_question_sources(question_id,routine,bank_name,module,source,source_reference,imported_at) VALUES (?,?,?,?,?,?,?)",
                                   (question_id, routine, bank, module, provenance, reference, imported)).lastrowid
        answers = source.execute("SELECT answer_text,answer_normalized,learned_at,reviewed_at FROM qa_answers WHERE question_id=?", (original_id,))
        target.executemany("INSERT INTO qa_source_answers(source_id,answer_text,answer_normalized,learned_at,reviewed_at) VALUES (?,?,?,?,?)",
                           ((source_id, answer, answer_key, learned, reviewed) for answer, answer_key, learned, reviewed in answers))
    target.execute("INSERT OR REPLACE INTO metadata VALUES (?,?)", ("source_question_records", str(source.execute("SELECT count(*) FROM qa_questions").fetchone()[0])))
    target.execute("INSERT OR REPLACE INTO metadata VALUES (?,?)", ("unique_question_sets", str(len(question_ids))))
    target.commit()
    target.execute("VACUUM")
    target.close()
    source.close()
    os.replace(compact_path, raw_path)


def routine_database_name(routine: str) -> str:
    return {
        "sagar-mein-yog": "smy.db",
        "ocean-learning-platform": "olp.db",
        "seagull-ces": "seagull.db",
        "seagull-cbt": "seagull.db",
    }[routine]


def answer_key(question: str, options: list[str]) -> str:
    payload = norm(question) + "\x1f" + "\x1e".join(sorted(norm(text_option(option)) for option in options))
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def split_routine_databases(master_path: Path) -> list[Path]:
    """Create compact routine databases. Source files in private/qa remain the audit record."""
    master = sqlite3.connect(master_path)
    outputs = {name: QA / name for name in {routine_database_name(row[0]) for row in master.execute("SELECT DISTINCT routine FROM qa_question_sources")}}
    # MarineSamraj public pages are learned at run time.  Bundle its empty routine database so
    # every machine begins with the same schema and still keeps learned data in its own file.
    outputs.setdefault("marinesamraj.db", QA / "marinesamraj.db")
    for path in outputs.values():
        if path.exists():
            path.unlink()
    databases = {name: sqlite3.connect(path) for name, path in outputs.items()}
    # Routine file schema version 3 (PRAGMA user_version), the same schema the app's in-place upgrade
    # (core:persistence QuestionAnswerSchema) gives older files: remote-sync bookkeeping columns, the
    # 'compass' answer source, the sync_state table (version 2) and the qa_authoritative table of
    # admin-corrected questions (version 3). Bundled rows leave the new columns and table empty. The
    # compass_* names and the 'compass' value are shipped storage identifiers (the Cosmos service's former
    # name), kept unchanged so existing user files keep working; changing them needs an approved migration.
    schema = """
        CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE qa_questions (
          id INTEGER PRIMARY KEY, bank_name TEXT NOT NULL, module TEXT NOT NULL,
          question_text TEXT NOT NULL, question_key TEXT NOT NULL,
          compass_entry_id TEXT, compass_revision INTEGER, compass_created INTEGER NOT NULL DEFAULT 0,
          UNIQUE(bank_name, module, question_key)
        );
        CREATE INDEX qa_questions_compass_entry ON qa_questions(compass_entry_id);
        CREATE TABLE qa_options (
          question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE,
          position INTEGER NOT NULL, option_text TEXT NOT NULL,
          PRIMARY KEY(question_id, position)
        );
        CREATE TABLE qa_answers (
          question_id INTEGER NOT NULL REFERENCES qa_questions(id) ON DELETE CASCADE,
          answer_text TEXT NOT NULL, answer_key TEXT NOT NULL,
          source TEXT NOT NULL CHECK(source IN ('source','learned','compass')),
          learned_at INTEGER, reviewed_at INTEGER,
          compass_revision INTEGER, client_id TEXT, submitted_at INTEGER, submit_status TEXT, confirmed_at INTEGER,
          PRIMARY KEY(question_id, answer_key)
        );
        CREATE UNIQUE INDEX qa_answers_client_id ON qa_answers(client_id);
        CREATE TABLE sync_state (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE qa_authoritative (
          bank_name TEXT NOT NULL, module TEXT NOT NULL, question_key TEXT NOT NULL,
          entry_id TEXT NOT NULL, revision INTEGER NOT NULL,
          PRIMARY KEY(bank_name, module, question_key)
        );
        PRAGMA user_version = 3;
    """
    for database in databases.values():
        database.execute("PRAGMA foreign_keys = ON")
        database.executescript(schema)
    source_counts = {name: 0 for name in outputs}
    for source_id, question_id, routine, bank, module, question in master.execute(
        "SELECT s.id,s.question_id,s.routine,s.bank_name,s.module,q.question_text FROM qa_question_sources s JOIN qa_questions q ON q.id=s.question_id ORDER BY s.id"
    ):
        name = routine_database_name(routine)
        database = databases[name]
        options = [row[0] for row in master.execute("SELECT option_text FROM qa_options WHERE question_id=? ORDER BY position", (question_id,))]
        key = answer_key(question, options)
        cursor = database.execute("INSERT OR IGNORE INTO qa_questions(bank_name,module,question_text,question_key) VALUES (?,?,?,?)", (bank, module, question, key))
        if cursor.rowcount:
            local_id = cursor.lastrowid
            database.executemany("INSERT INTO qa_options(question_id,position,option_text) VALUES (?,?,?)", ((local_id, position, option) for position, option in enumerate(options, 1)))
        else:
            local_id = database.execute("SELECT id FROM qa_questions WHERE bank_name=? AND module=? AND question_key=?", (bank, module, key)).fetchone()[0]
        answers = master.execute("SELECT answer_text,answer_normalized FROM qa_source_answers WHERE source_id=?", (source_id,))
        database.executemany("INSERT OR IGNORE INTO qa_answers(question_id,answer_text,answer_key,source,learned_at,reviewed_at) VALUES (?,?,?,'source',NULL,NULL)",
                             ((local_id, answer, answer_hash) for answer, answer_hash in answers))
        source_counts[name] += 1
    for name, database in databases.items():
        database.execute("INSERT INTO metadata VALUES ('format','autopilot-routine-qa.v1')")
        database.execute("INSERT INTO metadata VALUES ('routine_database',?)", (name,))
        database.execute("INSERT INTO metadata VALUES ('source_question_records',?)", (str(source_counts[name]),))
        database.execute("INSERT INTO metadata VALUES ('unique_question_sets',?)", (str(database.execute("SELECT count(*) FROM qa_questions").fetchone()[0]),))
        database.commit()
        database.execute("VACUUM")
        database.close()
    master.close()
    RUNTIME_RESOURCES.mkdir(parents=True, exist_ok=True)
    for path in outputs.values():
        shutil.copy2(path, RUNTIME_RESOURCES / path.name)
    return sorted(outputs.values())


if __name__ == "__main__":
    main()
