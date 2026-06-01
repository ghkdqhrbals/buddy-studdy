from __future__ import annotations

import asyncio
import logging
from uuid import uuid4

from .db import to_iso, utc_now

from .apns import APNsClient, APNsQuestion
from .config import Settings
from .crypto import KeyCipher
from .db import Database
from .openai_client import OpenAIQuestionClient

logger = logging.getLogger(__name__)


class QuestionScheduler:
    max_pending_questions = 3

    def __init__(self, settings: Settings, database: Database):
        self.settings = settings
        self.database = database
        self.cipher = KeyCipher(settings.backend_master_key)
        self.openai = OpenAIQuestionClient(settings.openai_model)
        self.apns = APNsClient(settings)
        self._task: asyncio.Task | None = None
        self._stopping = asyncio.Event()

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        self._stopping.set()
        if self._task is not None:
            await self._task

    async def run_once(self) -> int:
        sent_count = 0
        for row in self.database.due_schedules():
            device_id = row["device_id"]
            created_record_id: str | None = None
            try:
                pending_count = self.database.pending_record_count(device_id)
                if pending_count >= self.max_pending_questions:
                    self.database.defer_schedule(
                        device_id=device_id,
                        minutes=5,
                        error=f"Pending question limit reached ({pending_count}).",
                    )
                    logger.info(
                        "skipped scheduled question device_id=%s pending=%s",
                        device_id,
                        pending_count,
                    )
                    continue

                encrypted_key = row["openai_api_key_cipher"]
                api_key = self.settings.openai_api_key
                if encrypted_key:
                    api_key = self.cipher.decrypt(encrypted_key)
                if not api_key:
                    raise RuntimeError("No OpenAI API key configured for schedule.")

                generated = await self.openai.generate_question(
                    api_key=api_key,
                    topic=row["topic"],
                    difficulty_level=row["difficulty_level"],
                    language=row["app_language"] or row["language"],
                    custom_prompt=row["custom_prompt"] or "",
                    recent_questions=self.database.recent_questions(device_id),
                )
                record_id = str(uuid4())
                created_at = utc_now()
                created_record_id = record_id
                self.database.create_question(
                    device_id=device_id,
                    topic=row["topic"],
                    difficulty_level=row["difficulty_level"],
                    question=generated.question,
                    expected_answer_hint=generated.expected_answer_hint,
                    scheduled_for=row["next_due_at"],
                    source="scheduled",
                    status="ungraded",
                    question_id=record_id,
                    created_at=created_at,
                )
                await self.apns.send_question(
                    APNsQuestion(
                        record_id=record_id,
                        created_at=to_iso(created_at),
                        device_token=row["apns_token"],
                        environment=row["apns_environment"],
                        question=generated.question,
                        expected_answer_hint=generated.expected_answer_hint,
                        topic=row["topic"],
                        difficulty_level=row["difficulty_level"],
                        language=row["app_language"] or row["language"],
                        sound=row["notification_sound"],
                    )
                )
                self.database.mark_scheduled_delivery(
                    device_id=device_id,
                    record_id=record_id,
                    interval_minutes=row["interval_minutes"],
                )
                sent_count += 1
                logger.info("sent scheduled question device_id=%s", device_id)
            except Exception as error:
                if created_record_id is not None:
                    self.database.delete_record(device_id=device_id, record_id=created_record_id)
                self.database.mark_error(device_id=device_id, error=str(error))
                logger.warning("scheduled question failed device_id=%s error=%s", device_id, error)
        return sent_count

    async def _run_loop(self) -> None:
        while not self._stopping.is_set():
            try:
                await self.run_once()
            except Exception:
                logger.exception("scheduler iteration failed")
            try:
                await asyncio.wait_for(
                    self._stopping.wait(),
                    timeout=self.settings.scheduler_poll_seconds,
                )
            except TimeoutError:
                pass
