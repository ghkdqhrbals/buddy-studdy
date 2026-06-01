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


def as_utc_datetime(value: datetime | str) -> datetime:
    if isinstance(value, datetime):
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)
    normalized = value.replace("Z", "+00:00")
    parsed = datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=UTC)
    return parsed.astimezone(UTC)


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
            connection.execute("SET TIME ZONE 'UTC'")
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
                self._ensure_postgres_timestamp_columns(db)
                self._ensure_postgres_columns(db)
            else:
                db.executescript(self._sqlite_schema())
                self._ensure_sqlite_columns(db)

    def _timestamp(self, value: datetime) -> datetime | str:
        value = value.astimezone(UTC)
        if self.is_postgres:
            return value
        return to_iso(value)

    def _response_timestamp(self, value: datetime | str | None) -> str | None:
        if value is None:
            return None
        return to_iso(as_utc_datetime(value))

    @staticmethod
    def _ensure_postgres_timestamp_columns(db: Any) -> None:
        timestamp_columns = {
            "devices": ["created_at", "updated_at", "last_seen_at"],
            "schedules": ["next_due_at", "last_sent_at", "created_at", "updated_at"],
            "questions": [
                "scheduled_for",
                "sent_at",
                "created_at",
                "updated_at",
                "answered_at",
                "graded_at",
                "skipped_at",
                "deleted_at",
            ],
        }
        for table, columns in timestamp_columns.items():
            for column in columns:
                row = db.execute(
                    """
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = %s AND column_name = %s
                    """,
                    (table, column),
                ).fetchone()
                if row is None or row["data_type"] == "timestamp with time zone":
                    continue
                db.execute(
                    f"""
                    ALTER TABLE {table}
                    ALTER COLUMN {column} TYPE TIMESTAMPTZ
                    USING {column}::timestamptz
                    """
                )

    @staticmethod
    def _ensure_postgres_columns(db: Any) -> None:
        columns = {
            "schedules": {
                "custom_prompt": "TEXT NOT NULL DEFAULT ''",
                "app_language": "TEXT NOT NULL DEFAULT 'ko'",
                "openai_model": "TEXT NOT NULL DEFAULT 'gpt-5.4'",
                "max_history_count": "INTEGER NOT NULL DEFAULT 100",
            },
            "questions": {
                "answer": "TEXT",
                "score": "INTEGER",
                "is_correct": "BOOLEAN",
                "feedback": "TEXT",
                "explanation": "TEXT",
                "answered_at": "TIMESTAMPTZ",
                "graded_at": "TIMESTAMPTZ",
                "skipped_at": "TIMESTAMPTZ",
                "deleted_at": "TIMESTAMPTZ",
                "source": "TEXT NOT NULL DEFAULT 'scheduled'",
                "updated_at": "TIMESTAMPTZ",
            },
        }
        for table, table_columns in columns.items():
            existing = {
                row["column_name"]
                for row in db.execute(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = %s
                    """,
                    (table,),
                ).fetchall()
            }
            for column, definition in table_columns.items():
                if column in existing:
                    continue
                db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")

        db.execute("UPDATE questions SET updated_at = created_at WHERE updated_at IS NULL")
        db.execute("ALTER TABLE questions ALTER COLUMN updated_at SET NOT NULL")

    @staticmethod
    def _ensure_sqlite_columns(db: Any) -> None:
        columns = {
            "schedules": {
                "custom_prompt": "TEXT NOT NULL DEFAULT ''",
                "app_language": "TEXT NOT NULL DEFAULT 'ko'",
                "openai_model": "TEXT NOT NULL DEFAULT 'gpt-5.4'",
                "max_history_count": "INTEGER NOT NULL DEFAULT 100",
            },
            "questions": {
                "answer": "TEXT",
                "score": "INTEGER",
                "is_correct": "INTEGER",
                "feedback": "TEXT",
                "explanation": "TEXT",
                "answered_at": "TEXT",
                "graded_at": "TEXT",
                "skipped_at": "TEXT",
                "deleted_at": "TEXT",
                "source": "TEXT NOT NULL DEFAULT 'scheduled'",
                "updated_at": "TEXT",
            },
        }
        for table, table_columns in columns.items():
            existing = {row["name"] for row in db.execute(f"PRAGMA table_info({table})").fetchall()}
            for column, definition in table_columns.items():
                if column not in existing:
                    db.execute(f"ALTER TABLE {table} ADD COLUMN {column} {definition}")
        db.execute("UPDATE questions SET updated_at = created_at WHERE updated_at IS NULL OR updated_at = ''")

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
            custom_prompt TEXT NOT NULL DEFAULT '',
            app_language TEXT NOT NULL DEFAULT 'ko',
            openai_model TEXT NOT NULL DEFAULT 'gpt-5.4',
            max_history_count INTEGER NOT NULL DEFAULT 100,
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
            answer TEXT,
            score INTEGER,
            is_correct INTEGER,
            feedback TEXT,
            explanation TEXT,
            answered_at TEXT,
            graded_at TEXT,
            skipped_at TEXT,
            deleted_at TEXT,
            source TEXT NOT NULL DEFAULT 'scheduled',
            created_at TEXT NOT NULL,
            updated_at TEXT NOT NULL
        );

        CREATE INDEX IF NOT EXISTS idx_schedules_due
            ON schedules(enabled, next_due_at);
        CREATE INDEX IF NOT EXISTS idx_questions_device_created
            ON questions(device_id, created_at);
        CREATE INDEX IF NOT EXISTS idx_questions_device_status
            ON questions(device_id, status, deleted_at);
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
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL,
                last_seen_at TIMESTAMPTZ NOT NULL
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
                custom_prompt TEXT NOT NULL DEFAULT '',
                app_language TEXT NOT NULL DEFAULT 'ko',
                openai_model TEXT NOT NULL DEFAULT 'gpt-5.4',
                max_history_count INTEGER NOT NULL DEFAULT 100,
                openai_api_key_cipher TEXT,
                next_due_at TIMESTAMPTZ,
                last_sent_at TIMESTAMPTZ,
                last_error TEXT,
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
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
                scheduled_for TIMESTAMPTZ NOT NULL,
                sent_at TIMESTAMPTZ,
                status TEXT NOT NULL,
                error TEXT,
                answer TEXT,
                score INTEGER,
                is_correct BOOLEAN,
                feedback TEXT,
                explanation TEXT,
                answered_at TIMESTAMPTZ,
                graded_at TIMESTAMPTZ,
                skipped_at TIMESTAMPTZ,
                deleted_at TIMESTAMPTZ,
                source TEXT NOT NULL DEFAULT 'scheduled',
                created_at TIMESTAMPTZ NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL
            )
            """,
            "CREATE INDEX IF NOT EXISTS idx_schedules_due ON schedules(enabled, next_due_at)",
            "CREATE INDEX IF NOT EXISTS idx_questions_device_created ON questions(device_id, created_at)",
            "CREATE INDEX IF NOT EXISTS idx_questions_device_status ON questions(device_id, status, deleted_at)",
        ]

    def register_device(
        self,
        apns_token: str,
        platform: str,
        apns_environment: str,
        language: str,
        timezone: str,
    ) -> tuple[str, str]:
        now = self._timestamp(utc_now())
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
        now = self._timestamp(utc_now())
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
        custom_prompt: str,
        app_language: str,
        openai_model: str,
        max_history_count: int,
    ) -> str | None:
        now_dt = utc_now()
        now = self._timestamp(now_dt)
        next_due_dt = now_dt + timedelta(minutes=interval_minutes) if enabled else None
        next_due_at = self._timestamp(next_due_dt) if next_due_dt is not None else None
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
                    notification_sound, custom_prompt, app_language, openai_model,
                    max_history_count, openai_api_key_cipher, next_due_at,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(device_id) DO UPDATE SET
                    topic = excluded.topic,
                    difficulty_level = excluded.difficulty_level,
                    interval_minutes = excluded.interval_minutes,
                    enabled = excluded.enabled,
                    notification_sound = excluded.notification_sound,
                    custom_prompt = excluded.custom_prompt,
                    app_language = excluded.app_language,
                    openai_model = excluded.openai_model,
                    max_history_count = excluded.max_history_count,
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
                    custom_prompt,
                    app_language,
                    openai_model,
                    max_history_count,
                    cipher,
                    next_due_at,
                    now,
                    now,
                ),
            )
        return self._response_timestamp(next_due_at)

    def delete_device(self, device_id: str) -> None:
        with self._lock, self.connect() as db:
            db.execute(self._sql("DELETE FROM devices WHERE device_id = ?"), (device_id,))

    def get_schedule(self, device_id: str) -> Any | None:
        with self._lock, self.connect() as db:
            return db.execute(
                self._sql(
                    """
                    SELECT
                        s.*, d.language AS device_language, d.timezone
                    FROM schedules s
                    JOIN devices d ON d.device_id = s.device_id
                    WHERE s.device_id = ?
                    """
                ),
                (device_id,),
            ).fetchone()

    def schedule_settings_response(self, row: Any | None) -> dict[str, Any]:
        if row is None:
            return {
                "topic": "Swift",
                "difficultyLevel": 2,
                "intervalMinutes": 15,
                "enabled": False,
                "notificationSound": "default",
                "customPrompt": "",
                "appLanguage": "ko",
                "openaiModel": "gpt-5.4",
                "maxHistoryCount": 100,
                "openaiKeyConfigured": False,
                "nextDueAt": None,
                "lastError": None,
            }

        return {
            "topic": row["topic"],
            "difficultyLevel": row["difficulty_level"],
            "intervalMinutes": row["interval_minutes"],
            "enabled": bool(row["enabled"]),
            "notificationSound": row["notification_sound"],
            "customPrompt": row["custom_prompt"] or "",
            "appLanguage": row["app_language"] or row["device_language"] or "ko",
            "openaiModel": row["openai_model"] or "gpt-5.4",
            "maxHistoryCount": row["max_history_count"] or 100,
            "openaiKeyConfigured": bool(row["openai_api_key_cipher"]),
            "nextDueAt": self._response_timestamp(row["next_due_at"]),
            "lastError": row["last_error"],
        }

    def pending_record_count(self, device_id: str) -> int:
        with self._lock, self.connect() as db:
            row = db.execute(
                self._sql(
                    """
                    SELECT COUNT(*) AS count
                    FROM questions
                    WHERE device_id = ?
                      AND deleted_at IS NULL
                      AND skipped_at IS NULL
                      AND score IS NULL
                      AND status IN ('sent', 'ungraded')
                    """
                ),
                (device_id,),
            ).fetchone()
            return int(row["count"] if row is not None else 0)

    def recent_questions(self, device_id: str, limit: int = 80) -> list[str]:
        with self._lock, self.connect() as db:
            rows = db.execute(
                self._sql(
                    """
                    SELECT question
                    FROM questions
                    WHERE device_id = ?
                    ORDER BY created_at DESC
                    LIMIT ?
                    """
                ),
                (device_id, limit),
            ).fetchall()
            return [row["question"] for row in rows]

    def list_records(
        self,
        device_id: str,
        limit: int = 100,
        offset: int = 0,
        include_deleted: bool = False,
    ) -> tuple[list[dict[str, Any]], int]:
        where_deleted = "" if include_deleted else "AND deleted_at IS NULL"
        with self._lock, self.connect() as db:
            total_row = db.execute(
                self._sql(
                    f"""
                    SELECT COUNT(*) AS count
                    FROM questions
                    WHERE device_id = ? {where_deleted}
                    """
                ),
                (device_id,),
            ).fetchone()
            rows = db.execute(
                self._sql(
                    f"""
                    SELECT *
                    FROM questions
                    WHERE device_id = ? {where_deleted}
                    ORDER BY created_at DESC
                    LIMIT ? OFFSET ?
                    """
                ),
                (device_id, limit, offset),
            ).fetchall()
        return [self.study_record_response(row) for row in rows], int(total_row["count"] if total_row else 0)

    def get_record(self, device_id: str, record_id: str, include_deleted: bool = False) -> dict[str, Any] | None:
        deleted_clause = "" if include_deleted else "AND deleted_at IS NULL"
        with self._lock, self.connect() as db:
            row = db.execute(
                self._sql(
                    f"""
                    SELECT *
                    FROM questions
                    WHERE device_id = ? AND id = ? {deleted_clause}
                    """
                ),
                (device_id, record_id),
            ).fetchone()
        return self.study_record_response(row) if row is not None else None

    def create_question(
        self,
        device_id: str,
        topic: str,
        difficulty_level: int,
        question: str,
        expected_answer_hint: str | None,
        scheduled_for: datetime | str | None = None,
        sent_at: datetime | str | None = None,
        source: str = "manual",
        status: str = "ungraded",
        question_id: str | None = None,
        created_at: datetime | str | None = None,
    ) -> dict[str, Any]:
        created_dt = as_utc_datetime(created_at) if created_at is not None else utc_now()
        scheduled_dt = as_utc_datetime(scheduled_for) if scheduled_for is not None else created_dt
        sent_dt = as_utc_datetime(sent_at) if sent_at is not None else None
        row_id = question_id or str(uuid4())
        created = self._timestamp(created_dt)
        scheduled = self._timestamp(scheduled_dt)
        sent = self._timestamp(sent_dt) if sent_dt is not None else None
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    INSERT INTO questions (
                        id, device_id, question, hint, topic, difficulty_level,
                        scheduled_for, sent_at, status, source, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """
                ),
                (
                    row_id,
                    device_id,
                    question,
                    expected_answer_hint,
                    topic,
                    difficulty_level,
                    scheduled,
                    sent,
                    status,
                    source,
                    created,
                    created,
                ),
            )

        record = self.get_record(device_id, row_id)
        if record is None:
            raise RuntimeError("Inserted question could not be loaded.")
        return record

    def set_record_answer(self, device_id: str, record_id: str, answer: str) -> dict[str, Any] | None:
        now = self._timestamp(utc_now())
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE questions
                    SET answer = ?, answered_at = COALESCE(answered_at, ?), updated_at = ?
                    WHERE device_id = ? AND id = ? AND deleted_at IS NULL
                    """
                ),
                (answer, now, now, device_id, record_id),
            )
        return self.get_record(device_id, record_id)

    def grade_record(
        self,
        device_id: str,
        record_id: str,
        answer: str,
        score: int,
        is_correct: bool,
        feedback: str,
        explanation: str,
    ) -> dict[str, Any] | None:
        now = self._timestamp(utc_now())
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE questions
                    SET answer = ?, answered_at = COALESCE(answered_at, ?),
                        score = ?, is_correct = ?, feedback = ?, explanation = ?,
                        graded_at = ?, status = 'graded', updated_at = ?
                    WHERE device_id = ? AND id = ? AND deleted_at IS NULL
                    """
                ),
                (
                    answer,
                    now,
                    score,
                    is_correct if self.is_postgres else 1 if is_correct else 0,
                    feedback,
                    explanation,
                    now,
                    now,
                    device_id,
                    record_id,
                ),
            )
        return self.get_record(device_id, record_id)

    def skip_record(self, device_id: str, record_id: str) -> dict[str, Any] | None:
        now = self._timestamp(utc_now())
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE questions
                    SET skipped_at = ?, status = 'skipped', updated_at = ?
                    WHERE device_id = ? AND id = ? AND deleted_at IS NULL AND score IS NULL
                    """
                ),
                (now, now, device_id, record_id),
            )
        return self.get_record(device_id, record_id)

    def delete_record(self, device_id: str, record_id: str) -> None:
        now = self._timestamp(utc_now())
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE questions
                    SET deleted_at = ?, status = 'deleted', updated_at = ?
                    WHERE device_id = ? AND id = ?
                    """
                ),
                (now, now, device_id, record_id),
            )

    def clear_records(self, device_id: str) -> None:
        now = self._timestamp(utc_now())
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE questions
                    SET deleted_at = ?, status = 'deleted', updated_at = ?
                    WHERE device_id = ? AND deleted_at IS NULL
                    """
                ),
                (now, now, device_id),
            )

    def defer_schedule(self, device_id: str, minutes: int, error: str | None = None) -> None:
        now_dt = utc_now()
        now = self._timestamp(now_dt)
        next_due_at = self._timestamp(now_dt + timedelta(minutes=max(1, minutes)))
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE schedules
                    SET next_due_at = ?, last_error = ?, updated_at = ?
                    WHERE device_id = ?
                    """
                ),
                (next_due_at, error[:500] if error else None, now, device_id),
            )

    def study_record_response(self, row: Any) -> dict[str, Any]:
        grading_result = None
        if row["score"] is not None:
            grading_result = {
                "score": int(row["score"]),
                "isCorrect": bool(row["is_correct"]),
                "feedback": row["feedback"] or "",
                "explanation": row["explanation"] or "",
            }

        return {
            "id": row["id"],
            "question": {
                "question": row["question"],
                "expectedAnswerHint": row["hint"],
                "createdAt": self._response_timestamp(row["created_at"]),
            },
            "answer": row["answer"],
            "gradingResult": grading_result,
            "topic": row["topic"],
            "difficulty": row["difficulty_level"],
            "answeredAt": self._response_timestamp(row["answered_at"]),
            "status": row["status"],
        }

    def due_schedules(self, limit: int = 25) -> list[Any]:
        now = self._timestamp(utc_now())
        with self._lock, self.connect() as db:
            return list(
                db.execute(
                    self._sql(
                        """
                    SELECT
                        d.device_id, d.apns_token, d.apns_environment, d.language, d.timezone,
                        s.topic, s.difficulty_level, s.interval_minutes, s.notification_sound,
                        s.custom_prompt, s.app_language, s.openai_model, s.max_history_count,
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
        scheduled_for: datetime | str,
        question: str,
        expected_answer_hint: str | None,
        question_id: str | None = None,
        created_at: datetime | str | None = None,
    ) -> dict[str, Any]:
        now_dt = utc_now()
        now = self._timestamp(now_dt)
        next_due_at = self._timestamp(now_dt + timedelta(minutes=interval_minutes))
        created_dt = as_utc_datetime(created_at) if created_at is not None else now_dt
        created = self._timestamp(created_dt)
        row_id = question_id or str(uuid4())
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                INSERT INTO questions (
                    id, device_id, question, hint, topic, difficulty_level,
                    scheduled_for, sent_at, status, source, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """
                ),
                (
                    row_id,
                    device_id,
                    question,
                    expected_answer_hint,
                    topic,
                    difficulty_level,
                    scheduled_for,
                    now,
                    "ungraded",
                    "scheduled",
                    created,
                    created,
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
        record = self.get_record(device_id, row_id)
        if record is None:
            raise RuntimeError("Inserted scheduled question could not be loaded.")
        return record

    def mark_scheduled_delivery(self, device_id: str, record_id: str, interval_minutes: int) -> None:
        now_dt = utc_now()
        now = self._timestamp(now_dt)
        next_due_at = self._timestamp(now_dt + timedelta(minutes=interval_minutes))
        with self._lock, self.connect() as db:
            db.execute(
                self._sql(
                    """
                    UPDATE questions
                    SET sent_at = ?, updated_at = ?
                    WHERE device_id = ? AND id = ?
                    """
                ),
                (now, now, device_id, record_id),
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
        now = self._timestamp(now_dt)
        retry_at = self._timestamp(now_dt + timedelta(minutes=retry_minutes))
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
