from __future__ import annotations

import math
import re
import unicodedata
from typing import Any

from ..storage.models import as_utc_datetime


class TopicStatisticsService:
    @staticmethod
    def to_topic_key(topic: str, fallback_topic: str) -> str:
        display = (topic or "").strip() or fallback_topic
        expanded = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", display)
        expanded = re.sub(r"([A-Za-z])([0-9])", r"\1 \2", expanded)
        expanded = re.sub(r"([0-9])([A-Za-z])", r"\1 \2", expanded)
        folded = unicodedata.normalize("NFKD", expanded).casefold()
        return "".join(
            character
            for character in folded
            if character.isalpha() or character.isnumeric()
        ) or "study"

    @staticmethod
    def to_display_topic(topic: str, fallback_topic: str) -> str:
        trimmed = (topic or "").strip()
        return trimmed or fallback_topic

    @classmethod
    def preferred_topic(cls, records: list[dict[str, Any]], fallback_topic: str) -> str:
        summaries: dict[str, dict[str, Any]] = {}
        for record in records:
            name = cls.to_display_topic(record["topic"], fallback_topic)
            key = unicodedata.normalize("NFKD", name).casefold()
            latest = cls.stats_date(record)
            if key in summaries:
                summaries[key]["count"] += 1
                summaries[key]["latest"] = max(summaries[key]["latest"], latest)
            else:
                summaries[key] = {"name": name, "count": 1, "latest": latest}

        if not summaries:
            return fallback_topic

        return sorted(
            summaries.values(),
            key=lambda item: (-item["count"], -item["latest"].timestamp(), len(item["name"]), item["name"].casefold()),
        )[0]["name"]

    @staticmethod
    def topic_aliases(records: list[dict[str, Any]], fallback_topic: str) -> list[str]:
        names = {TopicStatisticsService.to_display_topic(record["topic"], fallback_topic) for record in records}
        return sorted(names, key=str.casefold)

    @staticmethod
    def stats_date(record: dict[str, Any]) -> datetime:
        return as_utc_datetime(record["answeredAt"] or record["question"]["createdAt"])

    @staticmethod
    def estimated_level(difficulty: int, score: int) -> float:
        level_value = float(difficulty) + (float(max(0, min(100, score))) - 70) / 35
        return min(max(level_value, 1), 10)

    @staticmethod
    def minimum_half_width(sample_count: int) -> float:
        if sample_count >= 8:
            return 0.3
        if sample_count >= 4:
            return 0.45
        return 0.65

    @classmethod
    def progress_for_level_value(cls, level_value: float) -> float:
        return min(max((level_value - 0.5) / 10, 0), 1)

    @classmethod
    def make_level_range(
        cls,
        center_level: float,
        average: int,
        sample_count: int,
        half_width: float,
    ) -> dict[str, Any]:
        clamped_center = min(max(center_level, 1), 10)
        lower_level = max(1, clamped_center - half_width)
        upper_level = min(10, clamped_center + half_width)
        lower_bound = cls.progress_for_level_value(lower_level)
        upper_bound = max(lower_bound + 0.025, cls.progress_for_level_value(upper_level))
        return {
            "level": round(clamped_center),
            "average": average,
            "sampleCount": sample_count,
            "centerLevel": clamped_center,
            "lowerBound": lower_bound,
            "upperBound": min(1, upper_bound),
        }

    @classmethod
    def level_range(cls, records: list[dict[str, Any]], scores: list[int]) -> dict[str, Any]:
        estimates = [cls.estimated_level(record["difficulty"], score) for record, score in zip(records, scores, strict=False)]
        center_level = sum(estimates) / len(estimates)
        if len(estimates) > 1:
            variance = sum((estimate - center_level) ** 2 for estimate in estimates) / (len(estimates) - 1)
        else:
            variance = 0

        evidence_spread = math.sqrt(variance)
        sample_uncertainty = 0.9 / math.sqrt(len(estimates))
        conflict_uncertainty = evidence_spread * 0.55
        minimum_half_width = cls.minimum_half_width(len(estimates))
        half_width = min(4.0, max(minimum_half_width, sample_uncertainty + conflict_uncertainty))
        average = round(sum(scores) / len(scores))

        return cls.make_level_range(center_level, average, len(estimates), half_width)

    @classmethod
    def topic_sort_key(cls, sort: str):
        normalized = sort.strip().lower()
        if normalized == "recent":
            return lambda stat: (-cls._topic_latest_timestamp(stat), stat["topic"].casefold())
        if normalized == "name":
            return lambda stat: (stat["topic"].casefold(),)
        if normalized == "count":
            return lambda stat: (-stat["count"], stat["topic"].casefold())
        return lambda stat: (-stat["levelRange"]["centerLevel"], -stat["count"], stat["topic"].casefold())

    @staticmethod
    def _topic_latest_timestamp(stat: dict[str, Any]) -> float:
        return as_utc_datetime(stat["latestAt"]).timestamp()

    @classmethod
    def topic_stat(
        cls,
        topic_key: str,
        records: list[dict[str, Any]],
        fallback_topic: str,
    ) -> dict[str, Any] | None:
        scored = [
            (record, record["gradingResult"]["score"])
            for record in records
            if record.get("gradingResult") is not None
        ]
        if not scored:
            return None

        scores = [max(0, min(100, int(score))) for _, score in scored]
        correct_count = sum(1 for record, _ in scored if record["gradingResult"].get("isCorrect") is True)
        latest_at = max(cls.stats_date(record) for record, _ in scored)

        return {
            "topicKey": topic_key,
            "topic": cls.preferred_topic([record for record, _ in scored], fallback_topic),
            "topicAliases": cls.topic_aliases([record for record, _ in scored], fallback_topic),
            "count": len(scores),
            "average": round(sum(scores) / len(scores)),
            "best": max(scores),
            "correctRate": round(correct_count / len(scores) * 100),
            "levelRange": cls.level_range([record for record, _ in scored], scores),
            "latestAt": as_utc_datetime(latest_at).isoformat(),
            "records": [record for record, _ in scored],
        }
