"""Guards the "always use the cheapest model by default" property directly, rather than
leaving it as an unverified claim in a docstring - a future edit to either pricing table that
accidentally makes a pricier model win must fail one of these tests."""

from __future__ import annotations

from pathlib import Path

from ai_assistant.config import (
    DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS,
    MODEL_PRICING_PER_MILLION_TOKENS,
    SERVICE_ENV_FILE,
    Settings,
    _cheapest_model,
)


def test_cheapest_model_picks_lowest_input_plus_output_price():
    pricing = {
        "expensive": (5.0, 30.0),
        "cheap": (1.0, 6.0),
        "mid": (2.5, 15.0),
    }
    assert _cheapest_model(pricing) == "cheap"


def test_cheapest_model_ignores_the_middle_element_of_a_longer_tuple():
    # DeepSeek-shaped 3-tuples: (cache_miss_input, cache_hit_input, output). The middle
    # (cache-hit) element must not affect the comparison - only [0] (worst-case input) and
    # [-1] (output) do.
    pricing = {
        "a": (1.0, 999.0, 1.0),  # worst-case total 2.0, despite a huge (irrelevant) middle value
        "b": (0.5, 0.0, 0.5),  # worst-case total 1.0
    }
    assert _cheapest_model(pricing) == "b"


def test_default_openai_model_is_the_cheapest_entry_in_the_pricing_table(monkeypatch):
    # _env_file=None so this exercises the real code default, not whatever a real ai-assistant
    # /.env on this machine happens to already say (it currently agrees, but that would make
    # this test pass for the wrong reason).
    monkeypatch.delenv("AI_ASSISTANT_OPENAI_MODEL", raising=False)
    cheapest = min(
        MODEL_PRICING_PER_MILLION_TOKENS,
        key=lambda name: sum(MODEL_PRICING_PER_MILLION_TOKENS[name]),
    )
    assert Settings(_env_file=None).openai_model == cheapest == "gpt-5.6-luna"


def test_default_deepseek_model_is_the_cheapest_entry_in_the_pricing_table(monkeypatch):
    monkeypatch.delenv("AI_ASSISTANT_DEEPSEEK_MODEL", raising=False)
    cheapest = min(
        DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS,
        key=lambda name: (
            DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS[name][0]
            + DEEPSEEK_MODEL_PRICING_PER_MILLION_TOKENS[name][-1]
        ),
    )
    assert Settings(_env_file=None).deepseek_model == cheapest == "deepseek-v4-flash"


def test_explicit_env_override_still_wins_over_the_cheapest_default(monkeypatch):
    monkeypatch.setenv("AI_ASSISTANT_DEEPSEEK_MODEL", "deepseek-v4-pro")
    assert Settings(_env_file=None).deepseek_model == "deepseek-v4-pro"


def test_default_dotenv_path_is_service_local_and_not_cwd_relative():
    configured_path = Path(Settings.model_config["env_file"])

    assert configured_path == SERVICE_ENV_FILE
    assert configured_path.is_absolute()
    assert configured_path.parent.name == "ai-assistant"


def test_unrelated_dotenv_values_cannot_break_settings(tmp_path):
    dotenv = tmp_path / ".env"
    dotenv.write_text(
        "POSTGRES_PORT=5433\nVALKEY_PORT=6380\nAI_ASSISTANT_PROVIDER=fake\n",
        encoding="utf-8",
    )

    settings = Settings(_env_file=dotenv)

    assert settings.provider == "fake"
