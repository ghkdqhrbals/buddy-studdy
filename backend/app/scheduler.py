from __future__ import annotations

import asyncio
import logging

from .apns import APNsClient, APNsQuestion
from .config import Settings
from .crypto import KeyCipher
from .db import Database
from .openai_client import OpenAIQuestionClient

logger = logging.getLogger(__name__)


class QuestionScheduler:
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
            try:
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
                    language=row["language"],
                )
                await self.apns.send_question(
                    APNsQuestion(
                        device_token=row["apns_token"],
                        question=generated.question,
                        hint=generated.hint,
                        topic=row["topic"],
                        difficulty_level=row["difficulty_level"],
                        language=row["language"],
                        sound=row["notification_sound"],
                    )
                )
                self.database.mark_sent(
                    device_id=device_id,
                    topic=row["topic"],
                    difficulty_level=row["difficulty_level"],
                    interval_minutes=row["interval_minutes"],
                    scheduled_for=row["next_due_at"],
                    question=generated.question,
                    hint=generated.hint,
                )
                sent_count += 1
                logger.info("sent scheduled question device_id=%s", device_id)
            except Exception as error:
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

