from app.openai_client import OpenAIQuestionClient
from app.openai_models import OPENAI_MODEL_OPTIONS


def _question_payload(model: str) -> dict:
    return OpenAIQuestionClient(model).question_request_payload(
        topic="SwiftUI",
        difficulty_level=5,
        language="en",
        custom_prompt="Ask practical questions.",
        recent_questions=["What is a View?"],
    )


def _grading_payload(model: str) -> dict:
    return OpenAIQuestionClient(model).grading_request_payload(
        topic="SwiftUI",
        difficulty_level=5,
        language="en",
        question="Explain @State.",
        expected_answer_hint="Local mutable view state.",
        answer="@State stores local view state.",
    )


def test_gpt5_family_payloads_include_supported_responses_options():
    for option in OPENAI_MODEL_OPTIONS:
        if not option.supports_reasoning:
            continue

        for payload in (_question_payload(option.id), _grading_payload(option.id)):
            assert payload["model"] == option.id
            assert payload["reasoning"] == {"effort": option.default_reasoning_effort or "medium"}
            assert payload["text"]["verbosity"] == "low"
            assert payload["text"]["format"]["type"] == "json_schema"


def test_non_reasoning_payloads_do_not_send_reasoning_or_verbosity():
    non_reasoning_models = [
        option.id
        for option in OPENAI_MODEL_OPTIONS
        if not option.supports_reasoning
    ]
    assert non_reasoning_models

    for model in non_reasoning_models:
        for payload in (_question_payload(model), _grading_payload(model)):
            assert payload["model"] == model
            assert "reasoning" not in payload
            assert "verbosity" not in payload["text"]
            assert payload["text"]["format"]["type"] == "json_schema"


def test_unknown_model_uses_minimal_responses_payload():
    payload = _question_payload("custom-model")

    assert payload["model"] == "custom-model"
    assert "reasoning" not in payload
    assert "verbosity" not in payload["text"]
    assert payload["text"]["format"]["name"] == "study_question"


def test_grading_payload_keeps_strict_schema_and_rubric():
    payload = _grading_payload("gpt-5.2")

    schema = payload["text"]["format"]["schema"]
    assert schema["required"] == ["score", "isCorrect", "feedback", "explanation"]
    assert schema["properties"]["score"]["minimum"] == 0
    assert schema["properties"]["score"]["maximum"] == 100
    assert "Do not praise a very low score" in payload["input"][1]["content"]
