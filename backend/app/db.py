from __future__ import annotations

import hashlib
import secrets
import sqlite3
import threading
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Iterator
from uuid import uuid4

import psycopg
from psycopg.rows import dict_row


def utc_now() -> datetime:
    return datetime.now(tz=UTC)


def to_iso(value: datetime) -> str:
    return value.astimezone(UTC).isoformat()


def from_iso(value: str) -> datetime:
    return datetime.fromisoformat(value).astimezone(UTC)


def hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


class Database:
    def __init__(self, path: str, url: str | None = None):
        self.path = path
        self.url = url
        self._lock = threading.RLock()

    @contextmanager
    def connect(self) -> Iterator[Any]:
        if self.url:
            connection = psycopg.connect(self.url, row_factory=dict_row, connect_timeout=10)
            try:
                yield connection
                connection.commit()
            finally:
                connection.close()
            return

        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        connection = sqlite3.connect(self.path, timeout=30)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        try:
            yield connection
            connection.commit()
        finally:
            connection.close()

    @property
    def is_postgres(self) -> bool:
        return bool(self.url)

    def _sql(self, query: str) -> str:
        if not self.is_postgres:
            return query
        return query.replace("?", "%s")

    def init(self) -> None:
        with self._lock, self.connect() as db:
            if self.is_postgres:
                for statement in self._postgres_schema():
                    db.execute(statement)
            else:
                db.executescript(self._sqlite_schema())

    @staticmethod
    def _sqlite_schema() -> str:
        return """
        PRAGMA journal_mode = WAL;
        CREATE TABLE IF NOT EXISTS devices (
            device_id TEXT PRIMARY KEY,
            client_secret_hash TEXT NOT NULL,
            apns_token TEXT NOT NULL,
            platform TEXT NOT NULL,
            apns_environment TEXT NOT NULL,
            language TEXT NOT NULL,
            timezone TEXT NOT NULL,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL,
            last_seen_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS schedules (
            device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
            topic TEXT NOT NULL,
            difficulty_level INTEGER NOT NULL,
            interval_minutes INTEGER NOT NULL,
            enabled INTEGER NOT NULL,
            notification_sound TEXT,
            openai_api_key_cipher TEXT,
            next_due_at TEXT,
            last_sent_at TEXT,
            last_error TEXT,
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS questions (
            id TEXT PRIMARY KEY,
            device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
            question TEXT NOT NULL,
            hint TEXT,
            topic TEXT NOT NULL,
            difficulty_level INTEGER NOT NULL,
            scheduled_for TEXT NOT NULL,
            sent_at TEXT,
            status TEXT NOT NULL,
            error TEXT,
            created_at TEXT NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_schedules_due
            ON schedules(enabled, next_due_at);
        CREATE INDEX IF NOT EXISTS idx_questions_device_created
            ON questions(device_id, created_at);
        """

    @staticmethod
    def _postgres_schema() -> list[str]:
        return [
            """
            CREATE TABLE IF NOT EXISTS devices (
                device_id TEXT PRIMARY KEY,
                client_secret_hash TEXT NOT NULL,
                apns_token TEXT NOT NULL,
                platform TEXT NOT NULL,
                apns_environment TEXT NOT NULL,
                language TEXT NOT NULL,
                timezone TEXT NOT NULL,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_seen_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS schedules (
                device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
                topic TEXT NOT NULL,
                difficulty_level INTEGER NOT NULL,
                interval_minutes INTEGER NOT NULL,
                enabled BOOLEAN NOT NULL,
                notification_sound TEXT,
                openai_api_key_cipher TEXT,
                next_due_at TEXT,
                last_sent_at TEXT,
                last_error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS questions (
                id TEXT PRIMARY KEY,
                device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
                question TEXT NOT NULL,
                hint TEXT,
                topic TEXT NOT NULL,
                difficulty_level INTEGER NOT NULL,
                scheduled_for TEXT NOT NULL,
                sent_at TEXT,
                status TEXT NOT NULL,
                error TEXT,
                created_at TEXT NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_schedules_due ON schedules(enabled, next_due_at)",
            "CREATE INDEX IF NOT EXISTS idx_questions_device_created ON questions(device_id, created_at)",
        ]

    def register_device(
        self,
        apns_token: str,
        platform: str,
        apns_environment: str,
        language: str,
        timezone: str,
    ) -> tuple[str, str]:
        now = to_iso(utc_now())
        device_id = str(uuid4())
        client_secret = secrets.token_urlsafe(32)
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                INSERT INTO devices (
                    device_id, client_secret_hash, apns_token, platform,
                    apns_environment, language, timezone, created_at, updated_at, last_seen_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                ),
                (
                    device_id,
                    hash_secret(client_secret),
                    apns_token,
                    platform,
                    apns_environment,
                    language,
                    timezone,
                    now,
                    now,
                    now,
                ),
            )
        return device_id, client_secret

    def authenticate_device(self, device_id: str, client_secret: str) -> bool:
        now = to_iso(utc_now())
        with self._lock, self.connect() as db:
            row = db.execute(
                self._sql("SELECT client_secret_hash FROM devices WHERE device_id = ?"),
                (device_id,),
            ).fetchone()
            if row is None or row["client_secret_hash"] != hash_secret(client_secret):
                return False
            db.execute(
                self._sql("UPDATE devices SET last_seen_at = ?, updated_at = ? WHERE device_id = ?"),
                (now, now, device_id),
            )
            return True

    def upsert_schedule(
        self,
        device_id: str,
        topic: str,
        difficulty_level: int,
        interval_minutes: int,
        enabled: bool,
        openai_api_key_cipher: str | None,
        notification_sound: str | None,
    ) -> str | None:
        now_dt = utc_now()
        now = to_iso(now_dt)
        next_due_at = to_iso(now_dt + timedelta(minutes=interval_minutes)) if enabled else None
        with self._lock, self.connect() as db:
            existing = db.execute(
                self._sql("SELECT openai_api_key_cipher FROM schedules WHERE device_id = ?"),
                (device_id,),
            ).fetchone()
            cipher = openai_api_key_cipher
            if cipher is None and existing is not None:
                cipher = existing["openai_api_key_cipher"]

            db.execute(
                self._sql(
                    """
                INSERT INTO schedules (
                    device_id, topic, difficulty_level, interval_minutes, enabled,
                    notification_sound, openai_api_key_cipher, next_due_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    topic = excluded.topic,
                    difficulty_level = excluded.difficulty_level,
                    interval_minutes = excluded.interval_minutes,
                    enabled = excluded.enabled,
                    notification_sound = excluded.notification_sound,
                    openai_api_key_cipher = excluded.openai_api_key_cipher,
                    next_due_at = excluded.next_due_at,
                    updated_at = excluded.updated_at,
                    last_error = NULL
                """
                ),
                (
                    device_id,
                    topic,
                    difficulty_level,
                    interval_minutes,
                    enabled if self.is_postgres else 1 if enabled else 0,
                    notification_sound,
                    cipher,
                    next_due_at,
                    now,
                    now,
                ),
            )
        return next_due_at

    def delete_device(self, device_id: str) -> None:
        with self._lock, self.connect() as db:
            db.execute(self._sql("DELETE FROM devices WHERE device_id = ?"), (device_id,))

    def due_schedules(self, limit: int = 25) -> list[Any]:
        now = to_iso(utc_now())
        with self._lock, self.connect() as db:
            return list(
                db.execute(
                    self._sql(
                        """
                    SELECT
                        d.device_id, d.apns_token, d.apns_environment, d.language, d.timezone,
                        s.topic, s.difficulty_level, s.interval_minutes, s.notification_sound,
                        s.openai_api_key_cipher, s.next_due_at
                    FROM schedules s
                    JOIN devices d ON d.device_id = s.device_id
                    WHERE s.enabled = ? AND s.next_due_at IS NOT NULL AND s.next_due_at <= ?
                    ORDER BY s.next_due_at ASC
                    LIMIT ?
                    """
                    ),
                    (True if self.is_postgres else 1, now, limit),
                )
            )

    def mark_sent(
        self,
        device_id: str,
        topic: str,
        difficulty_level: int,
        interval_minutes: int,
        scheduled_for: str,
        question: str,
        hint: str | None,
    ) -> None:
        now_dt = utc_now()
        now = to_iso(now_dt)
        next_due_at = to_iso(now_dt + timedelta(minutes=interval_minutes))
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                INSERT INTO questions (
                    id, device_id, question, hint, topic, difficulty_level,
                    scheduled_for, sent_at, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                ),
                (
                    str(uuid4()),
                    device_id,
                    question,
                    hint,
                    topic,
                    difficulty_level,
                    scheduled_for,
                    now,
                    "sent",
                    now,
                ),
            )
            db.execute(
                self._sql(
                    """
                UPDATE schedules
                SET next_due_at = ?, last_sent_at = ?, last_error = NULL, updated_at = ?
                WHERE device_id = ?
                """
                ),
                (next_due_at, now, now, device_id),
            )

    def mark_error(self, device_id: str, error: str, retry_minutes: int = 5) -> None:
        now_dt = utc_now()
        now = to_iso(now_dt)
        retry_at = to_iso(now_dt + timedelta(minutes=retry_minutes))
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                UPDATE schedules
                SET next_due_at = ?, last_error = ?, updated_at = ?
                WHERE device_id = ?
                """
                ),
                (retry_at, error[:500], now, device_id),
            )
