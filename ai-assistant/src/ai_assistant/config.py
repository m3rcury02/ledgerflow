"""Environment-driven configuration. See .env.example.

Model pricing is a real, verified snapshot (OpenAI's GPT-5.6 family, checked via live web
search on 2026-07-19), not invented - but pricing and model availability drift over time and
cannot be checked by any offline test in this repository. Verify against
https://developers.openai.com/api/docs/pricing before relying on the cost estimate for a real
budget, the same "verify before relying on it" caveat this plan's Milestone 5 already applied
to an RDS `engine_version`.

DeepSeek's model list (`deepseek-v4-flash`/`deepseek-v4-pro`) was confirmed live against
`GET https://api.deepseek.com/models` with a real key on 2026-07-21 - not assumed. Pricing below
came from https://api-docs.deepseek.com/quick_start/pricing on the same date and carries the
same drift caveat as the OpenAI table.
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

# USD per 1,000,000 tokens: (cache_miss_input, cache_hit_input, output). DeepSeek reports actual
# cache-hit/cache-miss token counts in each response's `usage`, so - unlike the OpenAI table
# above - the real post-call cost can use the cheaper cache-hit rate where it actually applied.
DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS: dict[str, tuple[float, float, float]] = {
    "deepseek-v4-flash": (0.14, 0.0028, 0.28),
    "deepseek-v4-pro": (0.435, 0.003625, 0.87),
}


def _cheapest_model(pricing: dict[str, tuple[float, ...]]) -> str:
    """The model with the lowest worst-case (input + output) price per 1M tokens in `pricing` -
    used as the default `*_model` setting below so the cheapest tier stays the default even if
    this table changes later, instead of relying on a hardcoded model name someone has to
    remember to keep in sync by hand. Every price tuple here has the worst-case input price
    first and the output price last (a 2-tuple for OpenAI, a 3-tuple with an extra cache-hit
    rate in the middle for DeepSeek), so `[0] + [-1]` is the right comparison for both shapes."""
    return min(pricing, key=lambda name: pricing[name][0] + pricing[name][-1])


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AI_ASSISTANT_", env_file=".env")

    provider: str = "fake"

    openai_api_key: str | None = None
    openai_model: str = Field(
        default_factory=lambda: _cheapest_model(MODEL_PRICING_PER_MILLION_TOKENS)
    )

    deepseek_api_key: str | None = None
    deepseek_model: str = Field(
        default_factory=lambda: _cheapest_model(DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS)
    )

    max_output_tokens: int = Field(default=800, gt=0)
    timeout_seconds: float = Field(default=20.0, gt=0)
    max_telemetry_chars: int = Field(default=4000, gt=0)
    max_cost_usd_per_request: float = Field(default=0.05, gt=0)


def get_settings() -> Settings:
    return Settings()
