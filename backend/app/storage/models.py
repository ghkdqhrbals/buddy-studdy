from __future__ import annotations

from datetime import UTC, datetime
from typing import TYPE_CHECKING

from sqlalchemy import ForeignKey, Index, String, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy.orm import declarative_base

from ..openai_models import DEFAULT_OPENAI_MODEL


Base = declarative_base()


class Device(Base):
    __tablename__ = "devices"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(String(191), unique=True, nullable=False, index=True)
    client_secret_hash: Mapped[str] = mapped_column(String(191), nullable=False)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    google_session_expires_at: Mapped[datetime | None] = mapped_column(nullable=True)
    apns_token: Mapped[str] = mapped_column(String(191), nullable=False)
    platform: Mapped[str] = mapped_column(String(32), nullable=False)
    apns_environment: Mapped[str] = mapped_column(String(32), nullable=False)
    language: Mapped[str] = mapped_column(String(16), nullable=False)
    timezone: Mapped[str] = mapped_column(String(64), nullable=False)
    created_at: Mapped[datetime] = mapped_column(nullable=False)
    updated_at: Mapped[datetime] = mapped_column(nullable=False)
    last_seen_at: Mapped[datetime] = mapped_column(nullable=False)

    schedules: Mapped[list["Schedule"]] = relationship(
        "Schedule",
        back_populates="device",
        cascade="all, delete-orphan",
        passive_deletes=True,
    )
    questions: Mapped[list["Question"]] = relationship(
        "Question",
        back_populates="device",
        cascade="all, delete-orphan",
        passive_deletes=True,
    )
    user: Mapped["User | None"] = relationship("User", back_populates="devices")
    user_devices: Mapped[list["UserDevice"]] = relationship(
        "UserDevice",
        back_populates="device",
        cascade="all, delete-orphan",
        passive_deletes=True,
    )


class User(Base):
    __tablename__ = "users"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    provider: Mapped[str] = mapped_column(String(32), nullable=False, default="ANONYMOUS")
    provider_id: Mapped[str] = mapped_column(String(191), nullable=False, index=True)
    password_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False, default="ANONYMOUS")
    email: Mapped[str] = mapped_column(String(320), nullable=False)
    display_name: Mapped[str] = mapped_column(String(120), nullable=False)
    avatar_url: Mapped[str | None] = mapped_column(String(1000), nullable=True)
    avatar_symbol_name: Mapped[str] = mapped_column(String(64), nullable=False, default="pixel-buddy")
    avatar_color_seed: Mapped[str] = mapped_column(String(64), nullable=False, default="avatar-color-mint")
    bio: Mapped[str] = mapped_column(String(500), nullable=False, default="")
    allow_public_questions: Mapped[bool] = mapped_column(nullable=False, default=True)
    created_at: Mapped[datetime] = mapped_column(nullable=False)
    updated_at: Mapped[datetime] = mapped_column(nullable=False)

    devices: Mapped[list[Device]] = relationship("Device", back_populates="user")
    user_devices: Mapped[list["UserDevice"]] = relationship(
        "UserDevice",
        back_populates="user",
        cascade="all, delete-orphan",
        passive_deletes=True,
    )

    __table_args__ = (
        UniqueConstraint("provider", "provider_id", name="uq_users_provider_provider_id"),
    )


class UserDevice(Base):
    __tablename__ = "user_devices"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=False, index=True)
    device_id: Mapped[str] = mapped_column(
        String(191),
        ForeignKey("devices.device_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    session_expires_at: Mapped[datetime | None] = mapped_column(nullable=True)
    created_at: Mapped[datetime] = mapped_column(nullable=False)
    updated_at: Mapped[datetime] = mapped_column(nullable=False)
    last_seen_at: Mapped[datetime] = mapped_column(nullable=False)

    user: Mapped[User] = relationship("User", back_populates="user_devices")
    device: Mapped[Device] = relationship("Device", back_populates="user_devices")

    __table_args__ = (
        UniqueConstraint("user_id", "device_id", name="uq_user_devices_user_device"),
    )


class Report(Base):
    __tablename__ = "reports"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    question_id: Mapped[int | None] = mapped_column(ForeignKey("questions.id", ondelete="SET NULL"), nullable=True, index=True)
    reporter_device_id: Mapped[str | None] = mapped_column(String(191), nullable=True, index=True)
    reporter_user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True)
    reason: Mapped[str] = mapped_column(String(120), nullable=False)
    message: Mapped[str] = mapped_column(String(1000), nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(nullable=False)


class Schedule(Base):
    __tablename__ = "schedules"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(
        String(191),
        ForeignKey("devices.device_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), nullable=True, index=True)
    topic: Mapped[str] = mapped_column(String(255), nullable=False)
    difficulty_level: Mapped[int] = mapped_column(nullable=False)
    interval_minutes: Mapped[int] = mapped_column(nullable=False)
    enabled: Mapped[bool] = mapped_column(nullable=False)
    notification_sound: Mapped[str | None] = mapped_column(String(64), nullable=True)
    custom_prompt: Mapped[str] = mapped_column(nullable=False, default="")
    app_language: Mapped[str] = mapped_column(String(16), nullable=False, default="ko")
    openai_model: Mapped[str] = mapped_column(String(64), nullable=False, default=DEFAULT_OPENAI_MODEL)
    max_history_count: Mapped[int] = mapped_column(nullable=False, default=100)
    is_question_public: Mapped[bool] = mapped_column(nullable=False, default=False)
    openai_api_key_cipher: Mapped[str | None] = mapped_column(nullable=True)
    next_due_at: Mapped[datetime | None] = mapped_column(nullable=True)
    last_sent_at: Mapped[datetime | None] = mapped_column(nullable=True)
    last_error: Mapped[str | None] = mapped_column(nullable=True)
    created_at: Mapped[datetime] = mapped_column(nullable=False)
    updated_at: Mapped[datetime] = mapped_column(nullable=False)

    device: Mapped[Device] = relationship("Device", back_populates="schedules")


class Question(Base):
    __tablename__ = "questions"

    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(
        String(191),
        ForeignKey("devices.device_id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="SET NULL"), nullable=True, index=True)
    question: Mapped[str] = mapped_column(nullable=False)
    hint: Mapped[str | None] = mapped_column(nullable=True)
    topic: Mapped[str] = mapped_column(String(255), nullable=False)
    difficulty_level: Mapped[int] = mapped_column(nullable=False)
    scheduled_for: Mapped[datetime] = mapped_column(nullable=False)
    sent_at: Mapped[datetime | None] = mapped_column(nullable=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    error: Mapped[str | None] = mapped_column(nullable=True)
    answer: Mapped[str | None] = mapped_column(nullable=True)
    score: Mapped[int | None] = mapped_column(nullable=True)
    is_correct: Mapped[bool | None] = mapped_column(nullable=True)
    feedback: Mapped[str | None] = mapped_column(nullable=True)
    explanation: Mapped[str | None] = mapped_column(nullable=True)
    answered_at: Mapped[datetime | None] = mapped_column(nullable=True)
    graded_at: Mapped[datetime | None] = mapped_column(nullable=True)
    skipped_at: Mapped[datetime | None] = mapped_column(nullable=True)
    deleted_at: Mapped[datetime | None] = mapped_column(nullable=True)
    source: Mapped[str] = mapped_column(String(64), nullable=False, default="scheduled")
    is_public: Mapped[bool] = mapped_column(nullable=False, default=False)
    created_at: Mapped[datetime] = mapped_column(nullable=False)
    updated_at: Mapped[datetime] = mapped_column(nullable=False)

    device: Mapped[Device] = relationship("Device", back_populates="questions")


Index("idx_schedules_due", Schedule.enabled, Schedule.next_due_at)
Index("idx_schedules_device_user", Schedule.device_id, Schedule.user_id, unique=True)
Index("idx_questions_device_created", Question.device_id, Question.created_at)
Index("idx_questions_user_created", Question.user_id, Question.created_at)
Index("idx_questions_device_status", Question.device_id, Question.status, Question.deleted_at)
Index("idx_questions_device_visible_created", Question.device_id, Question.deleted_at, Question.created_at.desc())
Index("idx_questions_device_pending", Question.device_id, Question.deleted_at, Question.skipped_at, Question.score, Question.status)
Index("idx_questions_device_scored_activity", Question.device_id, Question.deleted_at, Question.score, Question.answered_at, Question.created_at)
Index("idx_questions_public", Question.is_public, Question.deleted_at, Question.created_at.desc())


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
