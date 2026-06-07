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

from .models import Base, Device, Question, Report, Schedule, User, UserDevice, as_utc_datetime, to_iso
from ..openai_models import DEFAULT_OPENAI_MODEL
from ..services.stats_service import TopicStatisticsService


PROVIDER_ANONYMOUS = "ANONYMOUS"
PROVIDER_GOOGLE = "GOOGLE"
PROVIDER_EMAIL = "EMAIL"
USER_STATUS_ANONYMOUS = "ANONYMOUS"
USER_STATUS_ACTIVE = "ACTIVE"
USER_STATUS_WITHDRAWN = "WITHDRAWN"


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

    def _boolean_default_sql(self, value: bool) -> str:
        if self.engine.dialect.name == "postgresql":
            return "TRUE" if value else "FALSE"
        return "1" if value else "0"

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
    def scheduler_lock(self) -> Iterator[bool]:
        if not self.is_postgres:
            yield True
            return

        lock_id = abs(hash("buddystuddy:scheduler")) % (2**31 - 1)
        with self.engine.connect() as connection:
            transaction = connection.begin()
            try:
                acquired = (
                    connection.execute(text("SELECT pg_try_advisory_lock(:lock_id)"), {"lock_id": lock_id})
                    .scalar()
                )
                if not acquired:
                    transaction.commit()
                    yield False
                    return

                try:
                    yield True
                finally:
                    connection.execute(text("SELECT pg_advisory_unlock(:lock_id)"), {"lock_id": lock_id})
                transaction.commit()
            except Exception:
                transaction.rollback()
                raise

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
                if "user_id" not in schedule_columns:
                    session.execute(text("ALTER TABLE schedules ADD COLUMN user_id INTEGER"))
                    session.execute(
                        text(
                            "UPDATE schedules "
                            "SET user_id = (SELECT devices.user_id FROM devices WHERE devices.device_id = schedules.device_id) "
                            "WHERE user_id IS NULL"
                        )
                    )
                    schedule_columns.add("user_id")
                if "is_question_public" not in schedule_columns:
                    session.execute(
                        text(
                            "ALTER TABLE schedules ADD COLUMN is_question_public "
                            f"BOOLEAN NOT NULL DEFAULT {self._boolean_default_sql(False)}"
                        )
                    )
                schedule_indexes = {idx["name"] for idx in inspector.get_indexes("schedules")}
                if self.engine.dialect.name == "postgresql":
                    unique_constraints = inspector.get_unique_constraints("schedules")
                    for constraint in unique_constraints:
                        if constraint.get("column_names") == ["device_id"] and constraint.get("name"):
                            session.execute(
                                text(
                                    f'ALTER TABLE schedules DROP CONSTRAINT IF EXISTS "{constraint["name"]}"'
                                )
                            )
                if "idx_schedules_user_id" not in schedule_indexes:
                    session.execute(text("CREATE INDEX IF NOT EXISTS idx_schedules_user_id ON schedules (user_id)"))
                if "idx_schedules_due_device_user" not in schedule_indexes:
                    session.execute(
                        text(
                            "CREATE INDEX IF NOT EXISTS idx_schedules_due_device_user "
                            "ON schedules (enabled, next_due_at, device_id, user_id)"
                        )
                    )
                if "idx_schedules_device_user" not in schedule_indexes:
                    session.execute(
                        text(
                            "CREATE UNIQUE INDEX IF NOT EXISTS idx_schedules_device_user "
                            "ON schedules (device_id, user_id)"
                        )
                    )
            if "devices" in table_names:
                device_columns = {column["name"] for column in inspector.get_columns("devices")}
                if "user_id" not in device_columns:
                    session.execute(text("ALTER TABLE devices ADD COLUMN user_id INTEGER"))
                    device_columns.add("user_id")
                if "google_session_expires_at" not in device_columns:
                    session.execute(text("ALTER TABLE devices ADD COLUMN google_session_expires_at TIMESTAMP"))
                    session.execute(
                        text(
                            "UPDATE devices "
                            "SET google_session_expires_at = :expires_at "
                            "WHERE user_id IS NOT NULL AND google_session_expires_at IS NULL"
                        ),
                        {"expires_at": self._utc_now() + timedelta(days=90)},
                    )
                if "idx_devices_user_id" not in {idx["name"] for idx in inspector.get_indexes("devices")}:
                    session.execute(text("CREATE INDEX IF NOT EXISTS idx_devices_user_id ON devices (user_id)"))

            if "users" in table_names:
                user_columns = {column["name"] for column in inspector.get_columns("users")}
                if "provider" not in user_columns:
                    session.execute(
                        text(
                            "ALTER TABLE users ADD COLUMN provider "
                            f"VARCHAR(32) NOT NULL DEFAULT '{PROVIDER_GOOGLE}'"
                        )
                    )
                    user_columns.add("provider")
                if "provider_id" not in user_columns:
                    session.execute(text("ALTER TABLE users ADD COLUMN provider_id VARCHAR(191)"))
                    if "google_sub" in user_columns:
                        session.execute(text("UPDATE users SET provider_id = google_sub WHERE provider_id IS NULL"))
                    session.execute(
                        text("UPDATE users SET provider_id = 'legacy:' || CAST(id AS VARCHAR) WHERE provider_id IS NULL")
                    )
                    user_columns.add("provider_id")
                if "password_hash" not in user_columns:
                    session.execute(text("ALTER TABLE users ADD COLUMN password_hash VARCHAR(64)"))
                    user_columns.add("password_hash")
                if "status" not in user_columns:
                    session.execute(
                        text(
                            "ALTER TABLE users ADD COLUMN status "
                            f"VARCHAR(32) NOT NULL DEFAULT '{USER_STATUS_ACTIVE}'"
                        )
                    )
                    user_columns.add("status")
                if "google_sub" in user_columns:
                    if self.engine.dialect.name == "postgresql":
                        session.execute(text("ALTER TABLE users ALTER COLUMN google_sub DROP NOT NULL"))
                    session.execute(
                        text(
                            "UPDATE users "
                            "SET provider = :anonymous_provider, status = :anonymous_status "
                            "WHERE google_sub LIKE 'anonymous:%'"
                        ),
                        {
                            "anonymous_provider": PROVIDER_ANONYMOUS,
                            "anonymous_status": USER_STATUS_ANONYMOUS,
                        },
                    )
                    session.execute(
                        text(
                            "UPDATE users "
                            "SET provider = :google_provider, status = :active_status "
                            "WHERE google_sub NOT LIKE 'anonymous:%'"
                        ),
                        {
                            "google_provider": PROVIDER_GOOGLE,
                            "active_status": USER_STATUS_ACTIVE,
                        },
                    )
                if "idx_users_provider_id" not in {idx["name"] for idx in inspector.get_indexes("users")}:
                    session.execute(text("CREATE INDEX IF NOT EXISTS idx_users_provider_id ON users (provider, provider_id)"))
                if "allow_public_questions" not in user_columns:
                    session.execute(
                        text(
                            "ALTER TABLE users ADD COLUMN allow_public_questions "
                            f"BOOLEAN NOT NULL DEFAULT {self._boolean_default_sql(True)}"
                        )
                    )
                if "avatar_symbol_name" not in user_columns:
                    session.execute(
                        text("ALTER TABLE users ADD COLUMN avatar_symbol_name VARCHAR(64) NOT NULL DEFAULT 'pixel-buddy'")
                    )
                    user_columns.add("avatar_symbol_name")
                if "avatar_color_seed" not in user_columns:
                    session.execute(
                        text("ALTER TABLE users ADD COLUMN avatar_color_seed VARCHAR(64) NOT NULL DEFAULT 'avatar-color-mint'")
                    )
                    user_columns.add("avatar_color_seed")

            if "user_devices" in table_names:
                user_device_columns = {column["name"] for column in inspector.get_columns("user_devices")}
                if "last_login_at" not in user_device_columns:
                    session.execute(text("ALTER TABLE user_devices ADD COLUMN last_login_at TIMESTAMP"))
                    user_device_columns.add("last_login_at")
                if "idx_user_devices_user_id" not in {idx["name"] for idx in inspector.get_indexes("user_devices")}:
                    session.execute(text("CREATE INDEX IF NOT EXISTS idx_user_devices_user_id ON user_devices (user_id)"))
                if "idx_user_devices_device_id" not in {idx["name"] for idx in inspector.get_indexes("user_devices")}:
                    session.execute(text("CREATE INDEX IF NOT EXISTS idx_user_devices_device_id ON user_devices (device_id)"))
                session.execute(
                    text(
                        "INSERT INTO user_devices (user_id, device_id, session_expires_at, created_at, updated_at, last_seen_at) "
                        "SELECT devices.user_id, devices.device_id, devices.google_session_expires_at, "
                        "devices.created_at, devices.updated_at, devices.last_seen_at "
                        "FROM devices "
                        "WHERE devices.user_id IS NOT NULL "
                        "AND NOT EXISTS ("
                        "  SELECT 1 FROM user_devices "
                        "  WHERE user_devices.user_id = devices.user_id "
                        "  AND user_devices.device_id = devices.device_id"
                        ")"
                    )
                )

            if "questions" in table_names:
                question_columns = {column["name"] for column in inspector.get_columns("questions")}
                if "is_public" not in question_columns:
                    session.execute(
                        text(
                            "ALTER TABLE questions ADD COLUMN is_public "
                            f"BOOLEAN NOT NULL DEFAULT {self._boolean_default_sql(False)}"
                        )
                    )
                if "user_id" not in question_columns:
                    session.execute(text("ALTER TABLE questions ADD COLUMN user_id INTEGER"))
                    session.execute(
                        text(
                            "UPDATE questions "
                            "SET user_id = (SELECT devices.user_id FROM devices WHERE devices.device_id = questions.device_id) "
                            "WHERE user_id IS NULL"
                        )
                    )

            if "idx_questions_public" not in {idx["name"] for idx in inspector.get_indexes("questions")}:
                session.execute(
                    text(
                        "CREATE INDEX IF NOT EXISTS idx_questions_public "
                        "ON questions (is_public, deleted_at, created_at DESC)"
                    )
                )
            if "idx_questions_user_created" not in {idx["name"] for idx in inspector.get_indexes("questions")}:
                session.execute(
                    text(
                        "CREATE INDEX IF NOT EXISTS idx_questions_user_created "
                        "ON questions (user_id, created_at DESC)"
                    )
                )

    def _get_device(self, session: Session, device_id: str) -> Device | None:
        return session.query(Device).filter(Device.device_id == device_id).first()

    def _get_schedule(self, session: Session, device_id: str, user_id: int | None = None) -> Schedule | None:
        query = session.query(Schedule).filter(Schedule.device_id == device_id)
        if user_id is not None:
            schedule = query.filter(Schedule.user_id == user_id).first()
            if schedule is not None:
                return schedule
            return query.filter(Schedule.user_id.is_(None)).first()
        return query.order_by(Schedule.updated_at.desc(), Schedule.id.desc()).first()

    def _previous_user_was_anonymous(
        self,
        session: Session,
        previous_user_id: int | None,
        next_user_id: int,
    ) -> bool:
        if previous_user_id is None or int(previous_user_id) == int(next_user_id):
            return False
        previous_user = session.query(User).filter(User.id == int(previous_user_id)).first()
        return bool(previous_user is not None and previous_user.status == USER_STATUS_ANONYMOUS)

    def _migrate_anonymous_device_data_to_user(
        self,
        session: Session,
        *,
        device_id: str,
        previous_user_id: int | None,
        user: User,
        now: datetime,
    ) -> None:
        if not self._previous_user_was_anonymous(session, previous_user_id, int(user.id)):
            return

        (
            session.query(Question)
            .filter(Question.device_id == device_id, Question.user_id == previous_user_id)
            .update({"user_id": int(user.id), "updated_at": now}, synchronize_session=False)
        )

        previous_schedule = self._get_schedule(session, device_id, int(previous_user_id)) if previous_user_id else None
        existing_schedule = self._get_schedule(session, device_id, int(user.id))
        if previous_schedule is not None and existing_schedule is None:
            previous_schedule.user_id = int(user.id)
            previous_schedule.updated_at = now

    def _get_user_device(self, session: Session, user_id: int, device_id: str) -> UserDevice | None:
        return (
            session.query(UserDevice)
            .filter(UserDevice.user_id == user_id, UserDevice.device_id == device_id)
            .first()
        )

    def _attach_device_to_user(
        self,
        session: Session,
        *,
        device: Device,
        user: User,
        session_expires_at: datetime | None,
        now: datetime,
        record_login: bool = False,
    ) -> UserDevice:
        mapping = self._get_user_device(session, int(user.id), device.device_id)
        if mapping is None:
            mapping = UserDevice(
                user_id=int(user.id),
                device_id=device.device_id,
                session_expires_at=session_expires_at,
                created_at=now,
                updated_at=now,
                last_seen_at=now,
            )
            session.add(mapping)
        else:
            mapping.session_expires_at = session_expires_at
            mapping.updated_at = now
            mapping.last_seen_at = now

        if record_login:
            mapping.last_login_at = now

        # devices.user_id is the currently active user for this physical device.
        device.user_id = int(user.id)
        device.google_session_expires_at = session_expires_at
        device.updated_at = now
        device.last_seen_at = now
        return mapping

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
            session.flush()
            user = User(
                provider=PROVIDER_ANONYMOUS,
                provider_id=f"anonymous:{device_id}",
                status=USER_STATUS_ANONYMOUS,
                email=f"{device_id}@anonymous.buddystuddy.local",
                display_name="BuddyStuddy user",
                avatar_url=None,
                avatar_symbol_name="pixel-buddy",
                avatar_color_seed="avatar-color-mint",
                bio="",
                allow_public_questions=False,
                created_at=now,
                updated_at=now,
            )
            session.add(user)
            session.flush()
            self._attach_device_to_user(
                session,
                device=row,
                user=user,
                session_expires_at=now + timedelta(days=90),
                now=now,
            )

        return device_id, client_secret

    def ensure_anonymous_user_for_device(self, device_id: str) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            device = self._get_device(session, device_id)
            if device is None:
                return None
            principal = self._device_principal_row(session, device_id, now)
            if principal is not None:
                return self.user_profile_response(principal[0])

            provider_id = f"anonymous:{device_id}"
            user = (
                session.query(User)
                .filter(User.provider == PROVIDER_ANONYMOUS, User.provider_id == provider_id)
                .first()
            )
            if user is None:
                user = User(
                    provider=PROVIDER_ANONYMOUS,
                    provider_id=provider_id,
                    status=USER_STATUS_ANONYMOUS,
                    email=f"{device_id}@anonymous.buddystuddy.local",
                    display_name="BuddyStuddy user",
                    avatar_url=None,
                    avatar_symbol_name="pixel-buddy",
                    avatar_color_seed="avatar-color-mint",
                    bio="",
                    allow_public_questions=False,
                    created_at=now,
                    updated_at=now,
                )
                session.add(user)
                session.flush()

            self._attach_device_to_user(
                session,
                device=device,
                user=user,
                session_expires_at=now + timedelta(days=90),
                now=now,
            )
            session.flush()
            return self.user_profile_response(user)

    def _device_principal_row(self, session: Session, device_id: str, now: datetime) -> tuple[User, UserDevice] | None:
        row = (
            session.query(User, UserDevice)
            .join(UserDevice, UserDevice.user_id == User.id)
            .filter(UserDevice.device_id == device_id)
            .filter(User.status != USER_STATUS_WITHDRAWN)
            .filter((UserDevice.session_expires_at.is_(None)) | (UserDevice.session_expires_at > now))
            .order_by(UserDevice.last_seen_at.desc(), UserDevice.id.desc())
            .first()
        )
        return row

    def _user_principal_row(self, session: Session, user_id: int, now: datetime) -> tuple[User, UserDevice] | None:
        row = (
            session.query(User, UserDevice)
            .join(UserDevice, UserDevice.user_id == User.id)
            .filter(User.id == user_id)
            .filter(User.status != USER_STATUS_WITHDRAWN)
            .filter((UserDevice.session_expires_at.is_(None)) | (UserDevice.session_expires_at > now))
            .order_by(UserDevice.last_seen_at.desc(), UserDevice.id.desc())
            .first()
        )
        return row

    def get_device_principal(self, device_id: str) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            row = self._device_principal_row(session, device_id, now)
            if row is None:
                return None
            user, mapping = row
            if user.status == USER_STATUS_WITHDRAWN:
                return None
            is_anonymous = user.status == USER_STATUS_ANONYMOUS
            has_active_session = (
                not is_anonymous
                and mapping.session_expires_at is not None
                and as_utc_datetime(mapping.session_expires_at) > now
            )
            return {
                "device_id": mapping.device_id,
                "user_id": int(user.id),
                "userDeviceId": int(mapping.id),
                "isAnonymous": is_anonymous,
                "hasActiveGoogleSession": has_active_session,
            }

    def get_user_principal(self, user_id: int) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            row = self._user_principal_row(session, user_id, now)
            if row is None:
                return None
            user, mapping = row
            if user.status == USER_STATUS_WITHDRAWN:
                return None
            is_anonymous = user.status == USER_STATUS_ANONYMOUS
            return {
                "device_id": mapping.device_id,
                "user_id": int(user.id),
                "userDeviceId": int(mapping.id),
                "isAnonymous": is_anonymous,
                "hasActiveGoogleSession": bool(not is_anonymous),
            }

    def get_access_token_principal(self, user_id: int, user_device_id: int | None = None) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            if user_device_id is None:
                row = self._user_principal_row(session, user_id, now)
            else:
                row = (
                    session.query(User, UserDevice)
                    .join(UserDevice, UserDevice.user_id == User.id)
                    .filter(User.id == user_id, UserDevice.id == user_device_id)
                    .filter(User.status != USER_STATUS_WITHDRAWN)
                    .filter((UserDevice.session_expires_at.is_(None)) | (UserDevice.session_expires_at > now))
                    .first()
                )
            if row is None:
                return None
            user, mapping = row
            if user.status == USER_STATUS_WITHDRAWN:
                return None
            is_anonymous = user.status == USER_STATUS_ANONYMOUS
            return {
                "device_id": mapping.device_id,
                "user_id": int(user.id),
                "userDeviceId": int(mapping.id),
                "isAnonymous": is_anonymous,
                "hasActiveGoogleSession": bool(not is_anonymous),
            }

    def device_belongs_to_user(self, device_id: str, user_id: int) -> bool:
        now = self._utc_now()
        with self.connect() as session:
            row = self._get_user_device(session, user_id, device_id)
            return bool(
                row is not None
                and (row.session_expires_at is None or as_utc_datetime(row.session_expires_at) > now)
            )

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

    def device_has_user(self, device_id: str) -> bool:
        return self.device_has_active_google_session(device_id)

    def device_has_active_google_session(self, device_id: str) -> bool:
        now = self._utc_now()
        with self.connect() as session:
            principal = self._device_principal_row(session, device_id, now)
            if principal is None:
                return False
            user, mapping = principal
            return bool(
                user.status == USER_STATUS_ACTIVE
                and mapping.session_expires_at is not None
                and as_utc_datetime(mapping.session_expires_at) > now
            )

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
        user_id: int | None,
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
        is_question_public: bool = False,
    ) -> str | None:
        now = self._utc_now()
        with self.connect() as session:
            existing = self._get_schedule(session, device_id, user_id)

            if existing is not None and openai_api_key_cipher is None:
                cipher = existing.openai_api_key_cipher
            else:
                cipher = openai_api_key_cipher

            if existing is None:
                next_due_at = None if not enabled else now + timedelta(minutes=interval_minutes)
                session.add(
                    Schedule(
                        device_id=device_id,
                        user_id=user_id,
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
            existing.user_id = user_id
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

    def link_google_user_to_device(
        self,
        device_id: str,
        email: str,
        display_name: str,
        provider_id: str | None = None,
        avatar_url: str | None = None,
        google_sub: str | None = None,
    ) -> dict[str, Any] | None:
        now = self._utc_now()
        provider_id = (provider_id or google_sub or "").strip()
        if not provider_id:
            return None
        normalized_name = display_name.strip() or email.split("@")[0] or "BuddyStuddy user"
        with self.connect() as session:
            device = self._get_device(session, device_id)
            if device is None:
                return None

            previous_user_id = device.user_id
            user = (
                session.query(User)
                .filter(User.provider == PROVIDER_GOOGLE, User.provider_id == provider_id)
                .first()
            )
            if user is None:
                user = User(
                    provider=PROVIDER_GOOGLE,
                    provider_id=provider_id,
                    status=USER_STATUS_ACTIVE,
                    email=email,
                    display_name=normalized_name,
                    avatar_url=avatar_url,
                    avatar_symbol_name="pixel-buddy",
                    avatar_color_seed="avatar-color-mint",
                    bio="",
                    allow_public_questions=True,
                    created_at=now,
                    updated_at=now,
                )
                session.add(user)
                session.flush()
            else:
                user.status = USER_STATUS_ACTIVE
                user.provider = PROVIDER_GOOGLE
                user.provider_id = provider_id
                user.email = email
                user.display_name = user.display_name or normalized_name
                user.avatar_url = avatar_url or user.avatar_url
                user.updated_at = now

            self._attach_device_to_user(
                session,
                device=device,
                user=user,
                session_expires_at=now + timedelta(days=90),
                now=now,
                record_login=True,
            )
            self._migrate_anonymous_device_data_to_user(
                session,
                device_id=device_id,
                previous_user_id=previous_user_id,
                user=user,
                now=now,
            )
            session.flush()
            return self.user_profile_response(user)

    def link_email_user_to_device(
        self,
        device_id: str,
        email: str,
        password: str,
    ) -> tuple[dict[str, Any] | None, bool]:
        now = self._utc_now()
        normalized_email = email.strip().lower()
        password_hash = hashlib.sha256(password.encode("utf-8")).hexdigest()
        display_name = normalized_email.split("@")[0] or "BuddyStuddy user"

        with self.connect() as session:
            device = self._get_device(session, device_id)
            if device is None:
                return None, False

            previous_user_id = device.user_id
            user = (
                session.query(User)
                .filter(User.provider == PROVIDER_EMAIL, User.provider_id == normalized_email)
                .first()
            )
            if user is None:
                user = User(
                    provider=PROVIDER_EMAIL,
                    provider_id=normalized_email,
                    password_hash=password_hash,
                    status=USER_STATUS_ACTIVE,
                    email=normalized_email,
                    display_name=display_name,
                    avatar_url=None,
                    avatar_symbol_name="pixel-buddy",
                    avatar_color_seed="avatar-color-mint",
                    bio="",
                    allow_public_questions=True,
                    created_at=now,
                    updated_at=now,
                )
                session.add(user)
                session.flush()
            elif user.password_hash != password_hash:
                return None, True
            else:
                user.status = USER_STATUS_ACTIVE
                user.email = normalized_email
                user.display_name = user.display_name or display_name
                user.updated_at = now

            self._attach_device_to_user(
                session,
                device=device,
                user=user,
                session_expires_at=now + timedelta(days=90),
                now=now,
                record_login=True,
            )
            self._migrate_anonymous_device_data_to_user(
                session,
                device_id=device_id,
                previous_user_id=previous_user_id,
                user=user,
                now=now,
            )
            session.flush()
            return self.user_profile_response(user), False

    def email_user_exists(self, email: str) -> bool:
        normalized_email = email.strip().lower()
        with self.connect() as session:
            return (
                session.query(User.id)
                .filter(User.provider == PROVIDER_EMAIL, User.provider_id == normalized_email)
                .first()
                is not None
            )

    def get_device_profile(self, device_id: str) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            principal = self._device_principal_row(session, device_id, now)
            if principal is None:
                return None
            user, mapping = principal
            if (
                user.status != USER_STATUS_ACTIVE
                or mapping.session_expires_at is None
                or as_utc_datetime(mapping.session_expires_at) <= now
            ):
                return None
            return self.user_profile_response(user)

    def get_public_profile(self, user_id: int) -> dict[str, Any] | None:
        with self.connect() as session:
            user = session.query(User).filter(User.id == user_id).first()
            if user is None or user.status != USER_STATUS_ACTIVE:
                return None
            return self.user_profile_response(user)

    def update_device_profile(
        self,
        device_id: str,
        display_name: str | None = None,
        bio: str | None = None,
        allow_public_questions: bool | None = None,
        avatar_symbol_name: str | None = None,
        avatar_color_seed: str | None = None,
    ) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            principal = self._device_principal_row(session, device_id, now)
            if principal is None:
                return None
            user, mapping = principal
            if (
                user.status != USER_STATUS_ACTIVE
                or mapping.session_expires_at is None
                or as_utc_datetime(mapping.session_expires_at) <= now
            ):
                return None

            if display_name is not None:
                next_name = display_name.strip()
                if next_name:
                    user.display_name = next_name[:120]
            if bio is not None:
                user.bio = bio.strip()[:500]
            if allow_public_questions is not None:
                user.allow_public_questions = bool(allow_public_questions)
            if avatar_symbol_name is not None:
                next_avatar_symbol_name = avatar_symbol_name.strip()
                if next_avatar_symbol_name:
                    user.avatar_symbol_name = next_avatar_symbol_name[:64]
            if avatar_color_seed is not None:
                next_avatar_color_seed = avatar_color_seed.strip()
                if next_avatar_color_seed:
                    user.avatar_color_seed = next_avatar_color_seed[:64]
            user.updated_at = now
            mapping.updated_at = now
            mapping.last_seen_at = now
            session.flush()
            return self.user_profile_response(user)

    def withdraw_device_user(self, device_id: str) -> dict[str, Any] | None:
        now = self._utc_now()
        with self.connect() as session:
            device = self._get_device(session, device_id)
            if device is None:
                return None

            principal = self._device_principal_row(session, device_id, now)
            if principal is None:
                return None
            user, mapping = principal
            if user.status != USER_STATUS_ACTIVE:
                return None

            withdrawn_user_id = int(user.id)
            question_ids = [
                row_id
                for (row_id,) in session.query(Question.id)
                .filter(Question.user_id == withdrawn_user_id)
                .all()
            ]
            if question_ids:
                (
                    session.query(Report)
                    .filter(Report.question_id.in_(question_ids))
                    .delete(synchronize_session=False)
                )
                (
                    session.query(Question)
                    .filter(Question.id.in_(question_ids))
                    .delete(synchronize_session=False)
                )
            (
                session.query(Report)
                .filter(Report.reporter_user_id == withdrawn_user_id)
                .delete(synchronize_session=False)
            )
            (
                session.query(UserDevice)
                .filter(UserDevice.user_id == withdrawn_user_id)
                .delete(synchronize_session=False)
            )
            if device.user_id == withdrawn_user_id:
                device.user_id = None
                device.google_session_expires_at = None
                device.updated_at = now
                device.last_seen_at = now
            session.delete(user)
            session.flush()

            provider_id = f"anonymous:{device_id}"
            anonymous_user = (
                session.query(User)
                .filter(User.provider == PROVIDER_ANONYMOUS, User.provider_id == provider_id)
                .first()
            )
            if anonymous_user is None:
                anonymous_user = User(
                    provider=PROVIDER_ANONYMOUS,
                    provider_id=provider_id,
                    status=USER_STATUS_ANONYMOUS,
                    email=f"{device_id}@anonymous.buddystuddy.local",
                    display_name="BuddyStuddy user",
                    avatar_url=None,
                    avatar_symbol_name="pixel-buddy",
                    avatar_color_seed="avatar-color-mint",
                    bio="",
                    allow_public_questions=False,
                    created_at=now,
                    updated_at=now,
                )
                session.add(anonymous_user)
                session.flush()
            else:
                anonymous_user.status = USER_STATUS_ANONYMOUS
                anonymous_user.allow_public_questions = False
                anonymous_user.updated_at = now

            self._attach_device_to_user(
                session,
                device=device,
                user=anonymous_user,
                session_expires_at=now + timedelta(days=90),
                now=now,
            )
            session.flush()
            return self.user_profile_response(anonymous_user)

    def create_report(
        self,
        reporter_device_id: str,
        question_id: str,
        reason: str,
        message: str,
    ) -> dict[str, Any] | None:
        row_id = self._record_id_value(question_id)
        if row_id is None:
            return None
        now = self._utc_now()
        with self.connect() as session:
            question = session.query(Question).filter(Question.id == row_id, Question.deleted_at.is_(None)).first()
            if question is None:
                return None
            device = self._get_device(session, reporter_device_id)
            report = Report(
                question_id=question.id,
                reporter_device_id=reporter_device_id,
                reporter_user_id=device.user_id if device is not None else None,
                reason=reason.strip()[:120],
                message=message.strip()[:1000],
                created_at=now,
            )
            session.add(report)
            session.flush()
            return {
                "id": report.id,
                "question": question.question,
                "topic": question.topic,
                "authorDeviceId": question.device_id,
                "reporterDeviceId": reporter_device_id,
                "reason": report.reason,
                "message": report.message,
                "createdAt": self._response_timestamp(report.created_at),
            }

    def get_schedule(self, device_id: str, user_id: int | None = None) -> dict[str, Any] | None:
        with self.connect() as session:
            schedule = self._get_schedule(session, device_id, user_id)
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
                "isQuestionPublic": False,
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
            "isQuestionPublic": bool(row["is_question_public"] if "is_question_public" in row else False),
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

    def pending_record_count(self, device_id: str, user_id: int | None = None) -> int:
        with self.connect() as session:
            query = session.query(Question).filter(
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
            if user_id is not None:
                query = query.filter(Question.user_id == user_id)
            return int(query.count())

    def recent_questions(self, device_id: str, limit: int = 80, user_id: int | None = None) -> list[str]:
        with self.connect() as session:
            query = session.query(Question.question).filter(Question.device_id == device_id)
            if user_id is not None:
                query = query.filter(Question.user_id == user_id)
            rows = query.order_by(Question.created_at.desc()).limit(limit).all()
        return [row[0] for row in rows]

    def list_records(
        self,
        device_id: str,
        limit: int = 100,
        offset: int = 0,
        include_deleted: bool = False,
        user_id: int | None = None,
        include_ungraded: bool = True,
    ) -> tuple[list[dict[str, Any]], int]:
        with self.connect() as session:
            query = session.query(Question).filter(Question.device_id == device_id)
            if user_id is not None:
                query = query.filter(Question.user_id == user_id)
            if not include_deleted:
                query = query.filter(Question.deleted_at.is_(None))
            if not include_ungraded:
                query = query.filter(Question.score.isnot(None))
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
        exclude_device_id: str | None = None,
        limit: int = 20,
        offset: int = 0,
        topic: str | None = None,
    ) -> tuple[list[dict[str, Any]], int]:
        with self.connect() as session:
            query = session.query(Question, User).join(User, Question.user_id == User.id).filter(
                Question.deleted_at.is_(None),
                Question.is_public.is_(True),
                Question.status == "graded",
                User.status == USER_STATUS_ACTIVE,
                User.allow_public_questions.is_(True),
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

            return [self.community_question_response(question, author=author) for question, author in rows], int(total_row)

    def stats_response(
        self,
        device_id: str,
        user_id: int | None = None,
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
            if user_id is not None:
                query = query.filter(Question.user_id == user_id)
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

    def get_record(
        self,
        device_id: str,
        record_id: str,
        include_deleted: bool = False,
        user_id: int | None = None,
    ) -> dict[str, Any] | None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return None
        with self.connect() as session:
            query = session.query(Question).filter(Question.device_id == device_id, Question.id == row_id)
            if user_id is not None:
                query = query.filter(Question.user_id == user_id)
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
        expected_answer_hint: str | None = None,
        is_public: bool = False,
        user_id: int | None = None,
        scheduled_for: datetime | str | None = None,
        sent_at: datetime | str | None = None,
        source: str = "manual",
        status: str = "ungraded",
        created_at: datetime | str | None = None,
    ) -> dict[str, Any]:
        created_dt = as_utc_datetime(created_at) if created_at is not None else self._utc_now()
        scheduled_dt = as_utc_datetime(scheduled_for) if scheduled_for is not None else created_dt
        sent_dt = as_utc_datetime(sent_at) if sent_at is not None else None
        if user_id is None:
            principal = self.get_device_principal(device_id)
            user_id = int(principal["user_id"]) if principal is not None else None

        with self.connect() as session:
            row = Question(
                device_id=device_id,
                user_id=user_id,
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

        record = self.get_record(device_id, str(record_id), user_id=user_id)
        if record is None:
            raise RuntimeError("Inserted question could not be loaded.")
        return record

    def set_record_publicity(
        self,
        device_id: str,
        record_id: str,
        is_public: bool,
        user_id: int | None = None,
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
            if user_id is not None and row.user_id != user_id:
                return None
            row.is_public = bool(is_public)
            row.updated_at = now
        return self.get_record(device_id, record_id, user_id=user_id)

    def set_record_answer(
        self,
        device_id: str,
        record_id: str,
        answer: str,
        user_id: int | None = None,
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
            if row is not None and user_id is not None and row.user_id != user_id:
                return None
            if row is None:
                return None
            row.answer = answer
            if row.answered_at is None:
                row.answered_at = now
            row.updated_at = now
        return self.get_record(device_id, record_id, user_id=user_id)

    def grade_record(
        self,
        device_id: str,
        record_id: str,
        answer: str,
        score: int,
        is_correct: bool,
        feedback: str,
        explanation: str,
        user_id: int | None = None,
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
            if row is not None and user_id is not None and row.user_id != user_id:
                return None
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
        return self.get_record(device_id, record_id, user_id=user_id)

    def skip_record(self, device_id: str, record_id: str, user_id: int | None = None) -> dict[str, Any] | None:
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
            if row is not None and user_id is not None and row.user_id != user_id:
                return None
            if row is None:
                return None
            row.skipped_at = now
            row.status = "skipped"
            row.updated_at = now
        return self.get_record(device_id, record_id, user_id=user_id)

    def delete_record(self, device_id: str, record_id: str, user_id: int | None = None) -> None:
        row_id = self._record_id_value(record_id)
        if row_id is None:
            return
        with self.connect() as session:
            row = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.id == row_id)
                .first()
            )
            if row is not None and user_id is not None and row.user_id != user_id:
                return
            if row is None:
                return
            session.query(Report).filter(Report.question_id == row.id).delete(synchronize_session=False)
            session.delete(row)

    def clear_records(self, device_id: str, user_id: int | None = None) -> None:
        with self.connect() as session:
            rows = (
                session.query(Question)
                .filter(Question.device_id == device_id, Question.deleted_at.is_(None))
                .all()
            )
            if user_id is not None:
                rows = [row for row in rows if row.user_id == user_id]
            row_ids = [int(row.id) for row in rows]
            if row_ids:
                session.query(Report).filter(Report.question_id.in_(row_ids)).delete(synchronize_session=False)
            for row in rows:
                session.delete(row)

    def defer_schedule(self, device_id: str, minutes: int, error: str | None = None, user_id: int | None = None) -> None:
        now = self._utc_now()
        next_due_at = now + timedelta(minutes=max(1, minutes))
        with self.connect() as session:
            row = self._get_schedule(session, device_id, user_id)
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
            "isPublic": bool(row.is_public),
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
                    Device.user_id.is_not(None),
                    (Schedule.user_id == Device.user_id) | Schedule.user_id.is_(None),
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
                    "next_due_at": as_utc_datetime(row.next_due_at) if row.next_due_at is not None else None,
                    "user_id": device.user_id,
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
        is_public: bool = False,
        user_id: int | None = None,
        created_at: datetime | str | None = None,
    ) -> dict[str, Any]:
        now = self._utc_now()
        created_dt = as_utc_datetime(created_at) if created_at is not None else now
        if user_id is None:
            principal = self.get_device_principal(device_id)
            user_id = int(principal["user_id"]) if principal is not None else None
        with self.connect() as session:
            record = Question(
                device_id=device_id,
                user_id=user_id,
                question=question,
                hint=expected_answer_hint,
                topic=topic,
                difficulty_level=difficulty_level,
                scheduled_for=scheduled_for,
                sent_at=now,
                is_public=is_public,
                status="ungraded",
                source="scheduled",
                created_at=created_dt,
                updated_at=created_dt,
            )
            session.add(record)
            session.flush()
            record_id = record.id

            schedule = self._get_schedule(session, device_id, user_id)
            if schedule is not None:
                schedule.next_due_at = now + timedelta(minutes=interval_minutes)
                schedule.last_sent_at = now
                schedule.last_error = None
                schedule.updated_at = now

        created = self.get_record(device_id, str(record_id), user_id=user_id)
        if created is None:
            raise RuntimeError("Inserted scheduled question could not be loaded.")
        return created

    def mark_scheduled_delivery(
        self,
        device_id: str,
        record_id: str,
        interval_minutes: int,
        user_id: int | None = None,
    ) -> None:
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

            schedule = self._get_schedule(session, device_id, user_id)
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
        user_id: int | None = None,
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

            schedule = self._get_schedule(session, device_id, user_id)
            if schedule is not None:
                schedule.next_due_at = now + timedelta(minutes=interval_minutes)
                schedule.last_error = error[:500]
                schedule.updated_at = now

    def mark_error(self, device_id: str, error: str, retry_minutes: int = 5, user_id: int | None = None) -> None:
        now = self._utc_now()
        retry_at = now + timedelta(minutes=retry_minutes)
        with self.connect() as session:
            schedule = self._get_schedule(session, device_id, user_id)
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
            "user_id": schedule.user_id,
            "device_language": device.language,
            "timezone": device.timezone,
        }

    def community_question_response(self, row: Question, author: User | None = None) -> dict[str, Any]:
        if author is None and row.user_id is not None:
            with self.connect() as session:
                author = session.query(User).filter(User.id == row.user_id).first()
        if author is not None and author.status != USER_STATUS_ACTIVE:
            author = None
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
            "question": row.question,
            "answer": row.answer,
            "gradingResult": grading_result,
            "topic": row.topic,
            "difficultyLevel": row.difficulty_level,
            "status": row.status,
            "source": row.source,
            "createdAt": self._response_timestamp(row.created_at),
            "answeredAt": self._response_timestamp(row.answered_at),
            "author": self.user_profile_response(author) if author is not None else None,
        }

    def user_profile_response(self, row: User) -> dict[str, Any]:
        return {
            "id": int(row.id),
            "displayName": row.display_name,
            "bio": row.bio or "",
            "avatarUrl": None,
            "avatarSymbolName": row.avatar_symbol_name or "pixel-buddy",
            "avatarColorSeed": row.avatar_color_seed or "avatar-color-mint",
            "pageAccess": {
                "publicQuestions": bool(row.allow_public_questions),
                "statistics": True,
                "studyDetail": True,
                "records": True,
            },
        }

def hash_secret(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()
