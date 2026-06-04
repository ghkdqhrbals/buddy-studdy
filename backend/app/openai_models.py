"""OpenAI model catalog shared across backend request validation and scheduling.

These are curated model IDs known to expose the Responses API model selector.
The list is intentionally conservative and can be extended by adding new
entries when official docs change.
"""

from __future__ import annotations

from dataclasses import dataclass


DEFAULT_OPENAI_MODEL = "gpt-5.4"


@dataclass(frozen=True)
class OpenAIModelDescriptor:
    id: str
    display_name: str
    supports_text_verbosity: bool = False


OPENAI_MODEL_OPTIONS: tuple[OpenAIModelDescriptor, ...] = (
    OpenAIModelDescriptor(id="gpt-5.5", display_name="GPT-5.5", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-5.4", display_name="GPT-5.4", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-5.2", display_name="GPT-5.2", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-5.1", display_name="GPT-5.1", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-5", display_name="GPT-5", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-5-mini", display_name="GPT-5 mini", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-5-nano", display_name="GPT-5 nano", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-4.1", display_name="GPT-4.1", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-4.1-mini", display_name="GPT-4.1 mini", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-4.1-nano", display_name="GPT-4.1 nano", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-4o", display_name="GPT-4o", supports_text_verbosity=True),
    OpenAIModelDescriptor(id="gpt-4o-mini", display_name="GPT-4o mini", supports_text_verbosity=True),
)

OPENAI_MODEL_IDS: tuple[str, ...] = tuple(option.id for option in OPENAI_MODEL_OPTIONS)


def normalize_openai_model(raw_model: str | None) -> str:
    trimmed = (raw_model or "").strip()
    if not trimmed:
        return DEFAULT_OPENAI_MODEL
    return trimmed


def is_supported_openai_model(raw_model: str | None) -> bool:
    return (raw_model or "").strip() in OPENAI_MODEL_IDS


def supports_text_verbosity(model: str) -> bool:
    model_id = (model or "").strip()
    for option in OPENAI_MODEL_OPTIONS:
        if option.id == model_id:
            return option.supports_text_verbosity
    return False
