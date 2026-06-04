from __future__ import annotations

import hashlib
import secrets
import threading
from collections.abc import Callable
from contextlib import contextmanager
from functools import wraps
from inspect import signature
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, Iterator, TypeVar

from sqlalchemy import Index, asc, create_engine, func, text
from sqlalchemy import inspect
from sqlalchemy.orm import Session, sessionmaker

from .models import Base, Device, Question, Schedule, as_utc_datetime, to_iso
from ..openai_models import DEFAULT_OPENAI_MODEL
from ..services.stats_service import TopicStatisticsService


_EntityT = TypeVar("_EntityT", bound=Base)


def _get_db_instance(first_arg: Any, kwargs: dict[str, Any]) -> Database | None:
    """Resolve a `Database` instance from decorator call arguments."""
    if isinstance(first_arg, Database):
        return first_arg

    if isinstance(kwargs.get("db"), Database):
        return kwargs["db"]

    if first_arg is not None and hasattr(first_arg, "db") and isinstance(first_arg.db, Database):
        return first_arg.db

    return None


def transactional(func: Callable[..., Any]) -> Callable[..., Any]:
    """JPA-like decorator for transactional execution.

    Use this on database service methods that should run within a single commit/
    rollback boundary. If the wrapped function accepts a `session` or `db_session`
    parameter, the active SQLAlchemy session is injected automatically.

    Example:

        @transactional
        def create_user(self, user_name: str, session):
            ...

    The wrapped object can be a `Database` instance, an object that has a `db`
    attribute, or can receive `db` as a keyword argument.
    """

    parameters = signature(func).parameters
    supports_session = "session" in parameters
    supports_db_session = "db_session" in parameters and not supports_session
    has_db_arg = "db" in parameters

    @wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> Any:
        bound = signature(func).bind_partial(*args, **kwargs)
        bound_names = set(bound.arguments.keys())

        db = _get_db_instance(args[0] if args else None, kwargs)
        if db is None:
            raise TypeError(
                "transactional function requires a Database instance as first argument, "
                "a `db` keyword argument, or an object with a `db` attribute."
            )

        if has_db_arg and "db" not in bound_names:
            kwargs["db"] = db

        with db.transactional() as session:
            if supports_session and "session" not in bound_names:
                kwargs["session"] = session
            elif supports_db_session and "db_session" not in bound_names:
                kwargs["db_session"] = session

            return func(*args, **kwargs)

    return wrapper


class Database:
    def __init__(self, path: str, url: str | None = None):
        self.path = path
        self.url = url
        self._lock = threading.RLock()
        self.engine = self._create_engine()
        self._session_factory = sessionmaker(bind=self.engine, class_=Session, expire_on_commit=False)

    def _create_engine(self):
        if self.url:
            normalized_url = self.url
            if normalized_url.startswith("postgresql://"):
                normalized_url = normalized_url.replace("postgresql://", "postgresql+psycopg://", 1)
            return create_engine(normalized_url, future=True)

        db_path = Path(self.path)
        db_path.parent.mkdir(parents=True, exist_ok=True)
        return create_engine(f"sqlite:///{db_path.as_posix()}", future=True)

    @property
    def is_postgres(self) -> bool:
        return bool(self.url)

    @contextmanager
    def connect(self) -> Iterator[Session]:
        with self._lock:
            session = self._session_factory()
            try:
                yield session
                session.commit()
            except Exception:
                session.rollback()
                raise
            finally:
                session.close()

    @contextmanager
    def transactional(self) -> Iterator[Session]:
        """JPA-style explicit transaction scope.

        Use `with db.transactional() as session:` to make a commit/rollback boundary.
        """
        with self.connect() as session:
            yield session

    @staticmethod
    def _utc_now() -> datetime:
        return datetime.now(tz=UTC)

    @staticmethod
    def _response_timestamp(value: datetime | str | None) -> str | None:
        if value is None:
            return None
        return to_iso(as_utc_datetime(value))

    @staticmethod
    def _record_id_value(record_id: str | int) -> int | None:
        try:
            return int(str(record_id))
        except (TypeError, ValueError):
            return None

    # JPA-like API (save/insert/delete semantics)
    def save(self, entity: _EntityT) -> _EntityT:
        with self.connect() as session:
            merged = session.merge(entity)
            session.flush()
            return merged

    def insert(self, entity: _EntityT) -> _EntityT:
        return self.save(entity)

    def delete(self, entity: _EntityT) -> None:
        with self.connect() as session:
            session.delete(entity)

    def init(self) -> None:
        Base.metadata.create_all(self.engine)
        self._ensure_schema_compatibility()

    def _ensure_schema_compatibility(self) -> None:
        inspector = inspect(self.engine)
        with self.connect() as session:
            table_names = inspector.get_table_names()

            if "schedules" in table_names:
                schedule_columns = {column["name"] for column in inspector.get_columns("schedules")}
                if "is_question_public" not in schedule_columns:
                    session.execute(
                        text("ALTER TABLE schedules ADD COLUMN is_question_public BOOLEAN NOT NULL DEFAULT 1")
                    )

            if "questions" in table_names:
                question_columns = {column["name"] for column in inspector.get_columns("questions")}
                if "is_public" not in question_columns:
                    session.execute(
                        text("ALTER TABLE questions ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT 1")
                    )

            if "idx_questions_public" not in {idx["name"] for idx in inspector.get_indexes("questions")}:
                session.execute(
                    text(
                        "CREATE INDEX IF NOT EXISTS idx_questions_public "
                        "ON questions (is_public, deleted_at, created_at DESC)"
                    )
                )

    def _get_device(self, session: Session, device_id: str) -> Device | None:
        return session.query(Device).filter(Device.device_id == device_id).first()

    def _get_schedule(self, session: Session, device_id: str) -> Schedule | None:
        return session.query(Schedule).filter(Schedule.device_id == device_id).first()

    def register_device(
        self,
        apns_token: str,
        platform: str,
        apns_environment: str,
        language: str,
        timezone: str,
    ) -> tuple[str, str]:
        now = self._utc_now()
        device_id = str(secrets.token_urlsafe(24))
        client_secret = secrets.token_urlsafe(32)

        with self.connect() as session:
            row = Device(
                device_id=device_id,
                client_secret_hash=hash_secret(client_secret),
                apns_token=apns_token,
                platform=platform,
                apns_environment=apns_environment,
                language=language,
                timezone=timezone,
                created_at=now,
                updated_at=now,
                last_seen_at=now,
            )
            session.add(row)

        return device_id, client_secret

    def authenticate_device(self, device_id: str, client_secret: str) -> bool:
        now = self._utc_now()
        with self.connect() as session:
            row = self._get_device(session, device_id)
            if row is None:
                return False
            if row.client_secret_hash != hash_secret(client_secret):
                return False
            row.updated_at = now
            row.last_seen_at = now
            return True

    def update_device_push_token(self, device_id: str, apns_token: str, apns_environment: str) -> None:
        now = self._utc_now()
        with self.connect() as session:
            row = self._get_device(session, device_id)
            if row is None:
                return
            row.apns_token = apns_token
            row.apns_environment = apns_environment
            row.updated_at = now
            row.last_seen_at = now

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
        is_question_public: bool = True,
    ) -> str | None:
        now = self._utc_now()
        with self.connect() as session:
            existing = self._get_schedule(session, device_id)

            if existing is not None and openai_api_key_cipher is None:
                cipher = existing.openai_api_key_cipher
            else:
                cipher = openai_api_key_cipher

            if existing is None:
                next_due_at = None if not enabled else now + timedelta(minutes=interval_minutes)
                session.add(
                    Schedule(
                        device_id=device_id,
                        topic=topic,
                        difficulty_level=difficulty_level,
                        interval_minutes=interval_minutes,
                        enabled=enabled,
                        notification_sound=notification_sound,
                        custom_prompt=custom_prompt,
                        app_language=app_language,
                        openai_model=openai_model,
                        max_history_count=max_history_count,
                        is_question_public=is_question_public,
                        openai_api_key_cipher=cipher,
                        next_due_at=next_due_at,
                        created_at=now,
                        updated_at=now,
                    )
                )
                return self._response_timestamp(next_due_at)

            preserve_existing_due = (
                enabled
                and existing.enabled
                and existing.topic == topic
                and int(existing.difficulty_level) == difficulty_level
                and int(existing.interval_minutes) == interval_minutes
                and (existing.notification_sound or None) == notification_sound
                and (existing.custom_prompt or "") == custom_prompt
                and (existing.app_language or "") == app_language
                and (existing.openai_model or "") == openai_model
                and int(existing.max_history_count) == max_history_count
                and bool(existing.is_question_public) == is_question_public
            )

            if not enabled:
                next_due_at = None
            elif preserve_existing_due:
                next_due_at = existing.next_due_at
            else:
                next_due_at = now + timedelta(minutes=interval_minutes)

            existing.topic = topic
            existing.difficulty_level = difficulty_level
            existing.interval_minutes = interval_minutes
            existing.enabled = enabled
            existing.notification_sound = notification_sound
            existing.custom_prompt = custom_prompt
            existing.app_language = app_language
            existing.openai_model = openai_model
            existing.max_history_count = max_history_count
            existing.is_question_public = is_question_public
            existing.openai_api_key_cipher = cipher
            existing.next_due_at = next_due_at
            existing.updated_at = now
            existing.last_error = None
            return self._response_timestamp(next_due_at)

    def delete_device(self, device_id: str) -> None:
        with self.connect() as session:
            row = self._get_device(session, device_id)
            if row is not None:
                session.delete(row)

    def get_schedule(self, device_id: str) -> dict[str, Any] | None:
        with self.connect() as session:
            schedule = self._get_schedule(session, device_id)
            if schedule is None:
                return None
            device = self._get_device(session, device_id)
            if device is None:
                return None

            return self._schedule_row(schedule, device)

    def schedule_settings_response(self, row: dict[str, Any] | None) -> dict[str, Any]:
        if row is None:
            return {
                "topic": "Swift",
                "difficultyLevel": 2,
                "intervalMinutes": 15,
                "enabled": False,
                "notificationSound": "default",
                "customPrompt": "",
                "appLanguage": "ko",
                "openaiModel": DEFAULT_OPENAI_MODEL,
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
            "openaiModel": row["openai_model"] or DEFAULT_OPENAI_MODEL,
            "maxHistoryCount": row["max_history_count"] or 100,
            "isQuestionPublic": bool(row["is_question_public"] if "is_question_public" in row else True),
            "openaiKeyConfigured": bool(row["openai_api_key_cipher"]),
            "nextDueAt": self._response_timestamp(row["next_due_at"]),
            "lastError": row["last_error"],
        }

    def api_status_response(self, row: dict[str, Any] | None) -> dict[str, Any]:
        return {
            "openaiKeyConfigured": bool(row["openai_api_key_cipher"]) if row is not None else False,
            "openaiModel": (row["openai_model"] if row is not None else None) or DEFAULT_OPENAI_MODEL,
            "usageUrl": "https://platform.openai.com/usage",
            "billingUrl": "https://platform.openai.com/settings/organization/billing/overview",
            "creditsUrl": "https://platform.openai.com/settings/organization/billing/credit-grants",
        }

    def pending_record_count(self, device_id: str) -> int:
        with self.connect() as session:
            return int(
                session.query(Question)
                .filter(
                    Question.device_id == device_id,
                    Question.deleted_at.is_(None),
                    Question.skipped_at.is_(None),
                    Question.score.is_(None),
                    Question.status.in_(
                        [
                            "sent",
                            "ungraded",
                        ]
                    ),
                )
                .count()
            )

    def recent_questions(self, device_id: str, limit: int = 80) -> list[str]:
        with self.connect() as session:
            rows = (
                session.query(Question.question)
                .filter(Question.device_id == device_id)
                .order_by(Question.created_at.desc())
                .limit(limit)
                .all()
            )
        return [row[0] for row in rows]

    def list_records(
        self,
        device_id: str,
        limit: int = 100,
        offset: int = 0,
        include_deleted: bool = False,
    ) -> tuple[list[dict[str, Any]], int]:
        with self.connect() as session:
            query = session.query(Question).filter(Question.device_id == device_id)
            if not include_deleted:
                query = query.filter(Question.deleted_at.is_(None))
            total_row = query.count()
            rows = (
                query.order_by(Question.created_at.desc())
                .limit(limit)
                .offset(offset)
                .all()
            )
            return [self.study_record_response(row) for row in rows], int(total_row)

    def list_public_questions(
        self,
        exclude_device_id: str,
        limit: int = 20,
        offset: int = 0,
        topic: str | None = None,
    ) -> tuple[list[dict[str, Any]], int]:
        with self.connect() as session:
            query = session.query(Question).filter(
                Question.deleted_at.is_(None),
                Question.is_public.is_(True),
            )
            if exclude_device_id:
                query = query.filter(Question.device_id != exclude_device_id)

            if topic:
                normalized = f"%{topic.strip()}%"
                query = query.filter(Question.topic.ilike(normalized))

            total_row = query.count()
            rows = (
                query.order_by(Question.created_at.desc())
                .limit(limit)
                .offset(offset)
                .all()
            )

            return [self.community_question_response(row) for row in rows], int(total_row)

    def stats_response(
        self,
        device_id: str,
        start_at: datetime | str | None = None,
        end_at: datetime | str | None = None,
        search: str = "",
        sort: str = "level",
        limit: int = 8,
        offset: int = 0,
        fallback_topic: str = "Study",
    ) -> dict[str, Any]:
        with self.connect() as session:
            query = session.query(Question).filter(
                Question.device_id == device_id,
                Question.deleted_at.is_(None),
                Question.score.isnot(None),
            )
            if start_at is not None:
                query = query.filter(func.coalesce(Question.answered_at, Question.created_at) >= as_utc_datetime(start_at))
            if end_at is not None:
                query = query.filter(func.coalesce(Question.answered_at, Question.created_at) < as_utc_datetime(end_at))

            records = [
                self.study_record_response(row)
                for row in query.order_by(func.coalesce(Question.answered_at, Question.created_at).asc()).all()
            ]

        grouped: dict[str, list[dict[str, Any]]] = {}
        for record in records:
            key = TopicStatisticsService.to_topic_key(record["topic"], fallback_topic)
            grouped.setdefault(key, []).append(record)

        query_text = search.strip().casefold()
        query_key = TopicStatisticsService.to_topic_key(search, "") if search else ""
        topics = []
        for topic_key, topic_records in grouped.items():
            stat = TopicStatisticsService.topic_stat(topic_key, topic_records, fallback_topic)
            if stat is None:
                continue
            if query_text and not (
                query_text in stat["topic"].casefold()
                or any(query_text in alias.casefold() for alias in stat["topicAliases"])
                or query_key in stat["topicKey"]
            ):
                continue
            topics.append(stat)

        topics.sort(key=TopicStatisticsService.topic_sort_key(sort))
        total_topics = len(topics)
        return {
            "totalResponses": len(records),
            "totalTopics": total_topics,
            "topics": topics[offset : offset + limit],
            "limit": limit,
            "offset": offset,
            "generatedAt": self._response_timestamp(self._utc_now()),
        }

    def get_record(self, device_id: str, record_id: str, include_deleted: bool = False) -> dict[str, Any] | None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return None
        with self.connect() as session:
            query = session.query(Question).filter(Question.device_id == device_id, Question.id == row_id)
            if not include_deleted:
                query = query.filter(Question.deleted_at.is_(None))
            row = query.first()
            if row is None:
                return None
            return self.study_record_response(row)

    def create_question(
        self,
        device_id: str,
        topic: str,
        difficulty_level: int,
        question: str,
        expected_answer_hint: str | None,
        is_public: bool = True,
        scheduled_for: datetime | str | None = None,
        sent_at: datetime | str | None = None,
        source: str = "manual",
        status: str = "ungraded",
        created_at: datetime | str | None = None,
    ) -> dict[str, Any]:
        created_dt = as_utc_datetime(created_at) if created_at is not None else self._utc_now()
        scheduled_dt = as_utc_datetime(scheduled_for) if scheduled_for is not None else created_dt
        sent_dt = as_utc_datetime(sent_at) if sent_at is not None else None

        with self.connect() as session:
            row = Question(
                device_id=device_id,
                question=question,
                hint=expected_answer_hint,
                topic=topic,
                difficulty_level=difficulty_level,
                scheduled_for=scheduled_dt,
                sent_at=sent_dt,
                is_public=is_public,
                status=status,
                source=source,
                created_at=created_dt,
                updated_at=created_dt,
            )
            session.add(row)
            session.flush()
            record_id = row.id

        record = self.get_record(device_id, str(record_id))
        if record is None:
            raise RuntimeError("Inserted question could not be loaded.")
        return record

    def set_record_answer(self, device_id: str, record_id: str, answer: str) -> dict[str, Any] | None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return None
        now = self._utc_now()
        with self.connect() as session:
            row = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.id == row_id, Question.deleted_at.is_(None))
                .first()
            )
            if row is None:
                return None
            row.answer = answer
            if row.answered_at is None:
                row.answered_at = now
            row.updated_at = now
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
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return None
        now = self._utc_now()
        with self.connect() as session:
            row = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.id == row_id, Question.deleted_at.is_(None))
                .first()
            )
            if row is None:
                return None
            row.answer = answer
            row.answered_at = row.answered_at or now
            row.score = score
            row.is_correct = is_correct
            row.feedback = feedback
            row.explanation = explanation
            row.graded_at = now
            row.status = "graded"
            row.updated_at = now
        return self.get_record(device_id, record_id)

    def skip_record(self, device_id: str, record_id: str) -> dict[str, Any] | None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return None
        now = self._utc_now()
        with self.connect() as session:
            row = (
                session.query(Question)
                .filter(
                    Question.device_id == device_id,
                    Question.id == row_id,
                    Question.deleted_at.is_(None),
                    Question.score.is_(None),
                )
                .first()
            )
            if row is None:
                return None
            row.skipped_at = now
            row.status = "skipped"
            row.updated_at = now
        return self.get_record(device_id, record_id)

    def delete_record(self, device_id: str, record_id: str) -> None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return
        now = self._utc_now()
        with self.connect() as session:
            row = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.id == row_id)
                .first()
            )
            if row is None:
                return
            row.deleted_at = now
            row.status = "deleted"
            row.updated_at = now

    def clear_records(self, device_id: str) -> None:
        now = self._utc_now()
        with self.connect() as session:
            rows = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.deleted_at.is_(None))
                .all()
            )
            for row in rows:
                row.deleted_at = now
                row.status = "deleted"
                row.updated_at = now

    def defer_schedule(self, device_id: str, minutes: int, error: str | None = None) -> None:
        now = self._utc_now()
        next_due_at = now + timedelta(minutes=max(1, minutes))
        with self.connect() as session:
            row = self._get_schedule(session, device_id)
            if row is None:
                return
            row.next_due_at = next_due_at
            row.last_error = error[:500] if error else None
            row.updated_at = now

    def study_record_response(self, row: Question) -> dict[str, Any]:
        grading_result = None
        if row.score is not None:
            grading_result = {
                "score": int(row.score),
                "isCorrect": bool(row.is_correct),
                "feedback": row.feedback or "",
                "explanation": row.explanation or "",
            }

        return {
            "id": str(row.id),
            "question": {
                "question": row.question,
                "expectedAnswerHint": row.hint,
                "createdAt": self._response_timestamp(row.created_at),
            },
            "answer": row.answer,
            "gradingResult": grading_result,
            "topic": row.topic,
            "difficulty": row.difficulty_level,
            "answeredAt": self._response_timestamp(row.answered_at),
            "status": row.status,
        }

    def due_schedules(self, limit: int = 25) -> list[dict[str, Any]]:
        now = self._utc_now()
        with self.connect() as session:
            rows = (
                session.query(Schedule, Device)
                .join(Device, Schedule.device_id == Device.device_id)
                .filter(
                    Schedule.enabled.is_(True),
                    Schedule.next_due_at.is_not(None),
                    Schedule.next_due_at <= now,
                )
                .order_by(asc(Schedule.next_due_at))
                .limit(limit)
                .all()
            )
        result: list[dict[str, Any]] = []
        for row, device in rows:
            result.append(
                {
                    "device_id": row.device_id,
                    "apns_token": device.apns_token,
                    "apns_environment": device.apns_environment,
                    "language": device.language,
                    "timezone": device.timezone,
                    "topic": row.topic,
                    "difficulty_level": row.difficulty_level,
                    "interval_minutes": row.interval_minutes,
                    "notification_sound": row.notification_sound,
                    "custom_prompt": row.custom_prompt,
                    "app_language": row.app_language,
                    "openai_model": row.openai_model,
                    "max_history_count": row.max_history_count,
                    "is_question_public": bool(row.is_question_public),
                    "openai_api_key_cipher": row.openai_api_key_cipher,
                }
            )

        return result

    def mark_sent(
        self,
        device_id: str,
        topic: str,
        difficulty_level: int,
        interval_minutes: int,
        scheduled_for: datetime | str,
        question: str,
        expected_answer_hint: str | None,
        created_at: datetime | str | None = None,
    ) -> dict[str, Any]:
        now = self._utc_now()
        created_dt = as_utc_datetime(created_at) if created_at is not None else now
        with self.connect() as session:
            record = Question(
                device_id=device_id,
                question=question,
                hint=expected_answer_hint,
                topic=topic,
                difficulty_level=difficulty_level,
                scheduled_for=scheduled_for,
                sent_at=now,
                status="ungraded",
                source="scheduled",
                created_at=created_dt,
                updated_at=created_dt,
            )
            session.add(record)
            session.flush()
            record_id = record.id

            schedule = self._get_schedule(session, device_id)
            if schedule is not None:
                schedule.next_due_at = now + timedelta(minutes=interval_minutes)
                schedule.last_sent_at = now
                schedule.last_error = None
                schedule.updated_at = now

        created = self.get_record(device_id, str(record_id))
        if created is None:
            raise RuntimeError("Inserted scheduled question could not be loaded.")
        return created

    def mark_scheduled_delivery(self, device_id: str, record_id: str, interval_minutes: int) -> None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return
        now = self._utc_now()
        with self.connect() as session:
            record = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.id == row_id)
                .first()
            )
            if record is not None:
                record.sent_at = now
                record.updated_at = now

            schedule = self._get_schedule(session, device_id)
            if schedule is not None:
                schedule.next_due_at = now + timedelta(minutes=interval_minutes)
                schedule.last_sent_at = now
                schedule.last_error = None
                schedule.updated_at = now

    def mark_scheduled_question_created_without_delivery(
        self,
        device_id: str,
        record_id: str,
        interval_minutes: int,
        error: str,
    ) -> None:
        row_id = self._record_id_value(record_id)
        now = self._utc_now()
        with self.connect() as session:
            if row_id is not None:
                record = (
                    session.query(Question)
                    .filter(Question.device_id == device_id, Question.id == row_id)
                    .first()
                )
                if record is not None:
                    record.updated_at = now

            schedule = self._get_schedule(session, device_id)
            if schedule is not None:
                schedule.next_due_at = now + timedelta(minutes=interval_minutes)
                schedule.last_error = error[:500]
                schedule.updated_at = now

    def mark_error(self, device_id: str, error: str, retry_minutes: int = 5) -> None:
        now = self._utc_now()
        retry_at = now + timedelta(minutes=retry_minutes)
        with self.connect() as session:
            schedule = self._get_schedule(session, device_id)
            if schedule is None:
                return
            schedule.next_due_at = retry_at
            schedule.last_error = error[:500]
            schedule.updated_at = now

    def _schedule_row(self, schedule: Schedule, device: Device) -> dict[str, Any]:
        return {
            "device_id": schedule.device_id,
            "topic": schedule.topic,
            "difficulty_level": schedule.difficulty_level,
            "interval_minutes": schedule.interval_minutes,
            "enabled": schedule.enabled,
            "notification_sound": schedule.notification_sound,
            "custom_prompt": schedule.custom_prompt,
            "app_language": schedule.app_language,
            "openai_model": schedule.openai_model,
            "max_history_count": schedule.max_history_count,
            "openai_api_key_cipher": schedule.openai_api_key_cipher,
            "next_due_at": schedule.next_due_at,
            "created_at": schedule.created_at,
            "updated_at": schedule.updated_at,
            "last_error": schedule.last_error,
            "is_question_public": schedule.is_question_public,
            "device_language": device.language,
            "timezone": device.timezone,
        }

    def community_question_response(self, row: Question) -> dict[str, Any]:
        return {
            "id": str(row.id),
            "question": row.question,
            "topic": row.topic,
            "difficultyLevel": row.difficulty_level,
            "status": row.status,
            "source": row.source,
            "createdAt": self._response_timestamp(row.created_at),
        }

def hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
