from __future__ import annotations

import json

import httpx

from .models import GradingPayload, QuestionPayload


class OpenAIQuestionClient:
    def __init__(self, model: str):
        self.model = model

    async def generate_question(
        self,
        api_key: str,
        topic: str,
        difficulty_level: int,
        language: str,
        custom_prompt: str = "",
        recent_questions: list[str] | None = None,
    ) -> QuestionPayload:
        prompt_language = "Korean" if language == "ko" else "English"
        language_instruction = "한국어로 질문해." if language == "ko" else "Ask the question in English."
        recent_question_text = "\n".join(
            f"{index + 1}. {question}"
            for index, question in enumerate((recent_questions or [])[-80:])
        )
        payload = {
            "model": self.model,
            "input": [
                {
                    "role": "system",
                    "content": (
                        "You are BuddyStuddy, a concise study tutor. "
                        "Generate one short but meaningful study question. "
                        "Never repeat or closely paraphrase previous questions. "
                        "Return only valid JSON."
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        "Create one study question.\n\n"
                        f"Topic: {topic}\n"
                        f"Difficulty level: {difficulty_level}/10\n"
                        f"Language: {prompt_language}\n"
                        f"Teacher instruction: {custom_prompt or 'Ask a concise practical question.'}\n"
                        f"Question language instruction: {language_instruction}\n"
                        "Previous questions to avoid:\n"
                        f"{recent_question_text or 'None'}\n\n"
                        "Requirements:\n"
                        "- Return JSON only.\n"
                        f"- {language_instruction}\n"
                        f"- Write the question and expectedAnswerHint in {prompt_language}.\n"
                        "- The question should be concise and practical.\n"
                        "- Do not repeat or closely paraphrase any previous question.\n"
                        "- Vary the concept, angle, example, or required reasoning from previous questions.\n"
                        "- If the topic is broad, rotate through different subtopics."
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
                        "required": ["question", "expectedAnswerHint"],
                        "properties": {
                            "question": {"type": "string"},
                            "expectedAnswerHint": {"type": ["string", "null"]},
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

    async def grade_answer(
        self,
        api_key: str,
        topic: str,
        difficulty_level: int,
        language: str,
        question: str,
        expected_answer_hint: str | None,
        answer: str,
    ) -> GradingPayload:
        prompt_language = "Korean" if language == "ko" else "English"
        payload = {
            "model": self.model,
            "input": [
                {
                    "role": "system",
                    "content": (
                        "You are a strict but helpful AI teacher. "
                        f"Write feedback in {prompt_language}. Return only valid JSON."
                    ),
                },
                {
                    "role": "user",
                    "content": (
                        "Grade the user's answer.\n\n"
                        f"Topic: {topic}\n"
                        f"Difficulty level: {difficulty_level}/10\n"
                        f"Feedback language: {prompt_language}\n"
                        f"Question: {question}\n"
                        f"Expected hint: {expected_answer_hint or 'None'}\n"
                        f"User answer: {answer}\n\n"
                        "Scoring rubric:\n"
                        "- 90-100: correct and complete\n"
                        "- 70-89: mostly correct, minor gaps\n"
                        "- 40-69: partially correct, important gaps\n"
                        "- 10-39: mostly incorrect, only small relevant pieces\n"
                        "- 0-9: irrelevant, blank, or fundamentally wrong\n\n"
                        "Set isCorrect to true only when score is 70 or higher. "
                        "The feedback tone must match the numeric score. "
                        "Do not praise a very low score as correct or close."
                    ),
                },
            ],
            "text": {
                "format": {
                    "type": "json_schema",
                    "name": "grading_result",
                    "schema": {
                        "type": "object",
                        "additionalProperties": False,
                        "required": ["score", "isCorrect", "feedback", "explanation"],
                        "properties": {
                            "score": {"type": "integer", "minimum": 0, "maximum": 100},
                            "isCorrect": {"type": "boolean"},
                            "feedback": {"type": "string"},
                            "explanation": {"type": "string"},
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
        grading = GradingPayload.model_validate(parsed)
        score = min(max(grading.score, 0), 100)
        return GradingPayload(
            score=score,
            isCorrect=score >= 70,
            feedback=grading.feedback,
            explanation=grading.explanation,
        )

    @staticmethod
    def _extract_output_text(body: dict) -> str | None:
        for item in body.get("output", []):
            for content in item.get("content", []):
                if content.get("type") in {"output_text", "text"}:
                    text = content.get("text")
                    if text:
                        return text
        return None
