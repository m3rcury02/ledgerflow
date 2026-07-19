"""Environment-driven configuration. See .env.example.

Model pricing is a real, verified snapshot (OpenAI's GPT-5.6 family, checked via live web
search on 2026-07-19), not invented - but pricing and model availability drift over time and
cannot be checked by any offline test in this repository. Verify against
https://developers.openai.com/api/docs/pricing before relying on the cost estimate for a real
budget, the same "verify before relying on it" caveat this plan's Milestone 5 already applied
to an RDS `engine_version`.
"""

from __future__ import annotations

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

# USD per 1,000,000 tokens: (input, output).
MODEL_PRICING_PER_MILLION_TOKENS: dict[str, tuple[float, float]] = {
    "gpt-5.6-luna": (1.00, 6.00),
    "gpt-5.6-terra": (2.50, 15.00),
    "gpt-5.6-sol": (5.00, 30.00),
}


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_ASSISTANT_", env_file=".env")

    provider: str = "fake"

    openai_api_key: str | None = None
    openai_model: str = "gpt-5.6-luna"

    max_output_tokens: int = Field(default=800, gt=0)
    timeout_seconds: float = Field(default=20.0, gt=0)
    max_telemetry_chars: int = Field(default=4000, gt=0)
    max_cost_usd_per_request: float = Field(default=0.05, gt=0)


def get_settings() -> Settings:
    return Settings()
