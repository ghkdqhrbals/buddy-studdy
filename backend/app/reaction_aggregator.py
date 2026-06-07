from __future__ import annotations

import asyncio
import logging

from .config import Settings
from .db import Database


logger = logging.getLogger(__name__)


class QuestionReactionAggregator:
    def __init__(self, settings: Settings, database: Database):
        self.settings = settings
        self.database = database
        self._task: asyncio.Task | None = None
        self._stopping = asyncio.Event()

    @property
    def poll_seconds(self) -> int:
        return max(1, self.settings.reaction_aggregation_poll_seconds)

    @property
    def batch_size(self) -> int:
        return max(1, self.settings.reaction_aggregation_batch_size)

    def start(self) -> None:
        if self._task is None:
            self._task = asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        self._stopping.set()
        if self._task is not None:
            await self._task

    async def run_once(self) -> int:
        return await asyncio.to_thread(
            self.database.aggregate_question_reaction_events,
            self.batch_size,
        )

    async def reconcile_once(self) -> int:
        return await asyncio.to_thread(self.database.reconcile_question_stats)

    async def _run_loop(self) -> None:
        cycles_since_reconcile = 0
        while not self._stopping.is_set():
            try:
                processed = await self.run_once()
                cycles_since_reconcile += 1
                if processed > 0:
                    logger.info("aggregated question reaction events count=%s", processed)
                if cycles_since_reconcile >= max(1, self.settings.reaction_reconcile_every_cycles):
                    reconciled = await self.reconcile_once()
                    cycles_since_reconcile = 0
                    if reconciled > 0:
                        logger.info("reconciled question stats count=%s", reconciled)
            except Exception:
                logger.exception("question reaction aggregation iteration failed")

            try:
                await asyncio.wait_for(self._stopping.wait(), timeout=self.poll_seconds)
            except TimeoutError:
                pass
