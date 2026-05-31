from __future__ import annotations

import time
from dataclasses import dataclass

import httpx
import jwt

from .config import Settings


@dataclass(frozen=True)
class APNsQuestion:
    device_token: str
    question: str
    hint: str | None
    topic: str
    difficulty_level: int
    language: str
    sound: str | None


class APNsClient:
    def __init__(self, settings: Settings):
        settings.require_apns()
        self.settings = settings

    def _token(self) -> str:
        assert self.settings.apns_team_id
        assert self.settings.apns_key_id
        assert self.settings.apns_auth_key_p8
        return jwt.encode(
            {
                "iss": self.settings.apns_team_id,
                "iat": int(time.time()),
            },
            self.settings.apns_auth_key_p8,
            algorithm="ES256",
            headers={"kid": self.settings.apns_key_id},
        )

    async def send_question(self, item: APNsQuestion) -> None:
        title = "BuddyStuddy"
        body = item.question
        aps: dict = {
            "alert": {
                "title": title,
                "subtitle": f"{item.topic} · Level {item.difficulty_level}",
                "body": body,
            },
            "category": "STUDY_QUESTION",
            "thread-id": "StudyMate.question",
        }
        if item.sound:
            aps["sound"] = item.sound
        else:
            aps["sound"] = "default"

        payload = {
            "aps": aps,
            "question": item.question,
            "hint": item.hint,
            "topic": item.topic,
            "difficultyLevel": item.difficulty_level,
        }

        url = f"{self.settings.apns_host}/3/device/{item.device_token}"
        headers = {
            "authorization": f"bearer {self._token()}",
            "apns-topic": self.settings.apns_bundle_id,
            "apns-push-type": "alert",
            "apns-priority": "10",
        }

        async with httpx.AsyncClient(http2=True, timeout=15) as client:
            response = await client.post(url, headers=headers, json=payload)
            if response.status_code >= 300:
                raise RuntimeError(f"APNs failed: {response.status_code} {response.text}")

