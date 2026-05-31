from __future__ import annotations

import json

import httpx

from .models import QuestionPayload


class OpenAIQuestionClient:
    def __init__(self, model: str):
        self.model = model

    async def generate_question(
        self,
        api_key: str,
        topic: str,
        difficulty_level: int,
        language: str,
    ) -> QuestionPayload:
        prompt_language = "Korean" if language == "ko" else "English"
        payload = {
            "model": self.model,
            "input": [
                {
                    "role": "system",
                    "content": (
                        "You are BuddyStuddy, a concise study tutor. "
                        "Generate one short but meaningful study question. "
                        "Return only valid JSON."
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        f"Topic: {topic}\n"
                        f"Difficulty level: {difficulty_level}/10\n"
                        f"Language: {prompt_language}\n"
                        "Return JSON with keys question and hint. "
                        "The question should be answerable in a few sentences."
                    ),
                },
            ],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "study_question",
                    "schema": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["question", "hint"],
                        "properties": {
                            "question": {"type": "string"},
                            "hint": {"type": ["string", "null"]},
                        },
                    },
                }
            },
        }

        async with httpx.AsyncClient(timeout=30) as client:
            response = await client.post(
                "https://api.openai.com/v1/responses",
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            response.raise_for_status()
            body = response.json()

        output_text = body.get("output_text")
        if not output_text:
            output_text = self._extract_output_text(body)
        if not output_text:
            raise ValueError("OpenAI response did not include output text.")

        parsed = json.loads(output_text)
        return QuestionPayload.model_validate(parsed)

    @staticmethod
    def _extract_output_text(body: dict) -> str | None:
        for item in body.get("output", []):
            for content in item.get("content", []):
                if content.get("type") in {"output_text", "text"}:
                    text = content.get("text")
                    if text:
                        return text
        return None

