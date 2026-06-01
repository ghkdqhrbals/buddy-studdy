from __future__ import annotations

import sqlite3
import sys
from pathlib import Path
from typing import Any

from .config import Settings
from .db import Database


def _load_rows(source: sqlite3.Connection, table: str) -> list[sqlite3.Row]:
    exists = source.execute(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        (table,),
    ).fetchone()
    if not exists:
        return []
    return list(source.execute(f"SELECT * FROM {table}"))


def _value(row: sqlite3.Row, key: str, default: Any = None) -> Any:
    return row[key] if key in row.keys() else default


def migrate(sqlite_path: Path) -> None:
    settings = Settings.load()
    if not settings.database_url:
        print("DATABASE_URL is not set. SQLite migration skipped.")
        return

    if not sqlite_path.exists():
        print(f"SQLite source does not exist: {sqlite_path}")
        return

    target = Database(path=settings.database_path, url=settings.database_url)
    target.init()

    source = sqlite3.connect(sqlite_path)
    source.row_factory = sqlite3.Row
    try:
        devices = _load_rows(source, "devices")
        schedules = _load_rows(source, "schedules")
        questions = _load_rows(source, "questions")

        with target.connect() as db:
            for row in devices:
                db.execute(
                    """
                    INSERT INTO devices (
                        device_id, client_secret_hash, apns_token, platform,
                        apns_environment, language, timezone, created_at, updated_at, last_seen_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT(device_id) DO UPDATE SET
                        client_secret_hash = excluded.client_secret_hash,
                        apns_token = excluded.apns_token,
                        platform = excluded.platform,
                        apns_environment = excluded.apns_environment,
                        language = excluded.language,
                        timezone = excluded.timezone,
                        updated_at = excluded.updated_at,
                        last_seen_at = excluded.last_seen_at
                    """,
                    (
                        row["device_id"],
                        row["client_secret_hash"],
                        row["apns_token"],
                        row["platform"],
                        row["apns_environment"],
                        row["language"],
                        row["timezone"],
                        row["created_at"],
                        row["updated_at"],
                        row["last_seen_at"],
                    ),
                )

            for row in schedules:
                db.execute(
                    """
                    INSERT INTO schedules (
                        device_id, topic, difficulty_level, interval_minutes, enabled,
                        notification_sound, openai_api_key_cipher, next_due_at, last_sent_at,
                        last_error, created_at, updated_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT(device_id) DO UPDATE SET
                        topic = excluded.topic,
                        difficulty_level = excluded.difficulty_level,
                        interval_minutes = excluded.interval_minutes,
                        enabled = excluded.enabled,
                        notification_sound = excluded.notification_sound,
                        openai_api_key_cipher = excluded.openai_api_key_cipher,
                        next_due_at = excluded.next_due_at,
                        last_sent_at = excluded.last_sent_at,
                        last_error = excluded.last_error,
                        updated_at = excluded.updated_at
                    """,
                    (
                        row["device_id"],
                        row["topic"],
                        row["difficulty_level"],
                        row["interval_minutes"],
                        bool(row["enabled"]),
                        row["notification_sound"],
                        row["openai_api_key_cipher"],
                        row["next_due_at"],
                        _value(row, "last_sent_at"),
                        _value(row, "last_error"),
                        row["created_at"],
                        row["updated_at"],
                    ),
                )

            for row in questions:
                db.execute(
                    """
                    INSERT INTO questions (
                        id, device_id, question, hint, topic, difficulty_level,
                        scheduled_for, sent_at, status, error, created_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON CONFLICT(id) DO UPDATE SET
                        device_id = excluded.device_id,
                        question = excluded.question,
                        hint = excluded.hint,
                        topic = excluded.topic,
                        difficulty_level = excluded.difficulty_level,
                        scheduled_for = excluded.scheduled_for,
                        sent_at = excluded.sent_at,
                        status = excluded.status,
                        error = excluded.error,
                        created_at = excluded.created_at
                    """,
                    (
                        row["id"],
                        row["device_id"],
                        row["question"],
                        row["hint"],
                        row["topic"],
                        row["difficulty_level"],
                        row["scheduled_for"],
                        row["sent_at"],
                        row["status"],
                        _value(row, "error"),
                        row["created_at"],
                    ),
                )
        print(
            "SQLite migration finished: "
            f"devices={len(devices)} schedules={len(schedules)} questions={len(questions)}"
        )
    finally:
        source.close()


def main() -> None:
    sqlite_path = Path(sys.argv[1] if len(sys.argv) > 1 else "/legacy/buddystuddy.db")
    migrate(sqlite_path)


if __name__ == "__main__":
    main()
