"""
LLM 분석 모듈 — Claude API로 차이점 분석 + config 수정 제안 생성
"""
import json
import logging
import os
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")


async def suggest_fix(
    passed_features: Dict[str, Any],
    blocked_features: Dict[str, Any],
    shap_results: Dict[str, Any],
    current_config: Dict[str, Any],
) -> Dict[str, Any]:
    """
    Claude API로 차이점 분석 + 구체적 config 수정 JSON 생성.

    Args:
        passed_features: 통과 디바이스(S24 등) 평균 피처
        blocked_features: 차단 디바이스(S10 등) 평균 피처
        shap_results: SHAP 분석 결과 (top_differences)
        current_config: 현재 서버 config (headers.json, user_agents.json)

    Returns:
        {
            "analysis": "차단 원인 분석...",
            "root_cause": "Chrome 119 UA + sec-ch-ua-platform-version 불일치",
            "config_patches": [...],
            "confidence": 0.85
        }
    """
    if not ANTHROPIC_API_KEY:
        logger.warning("ANTHROPIC_API_KEY 미설정 — LLM 분석 스킵")
        return {
            "analysis": "ANTHROPIC_API_KEY not configured",
            "root_cause": "unknown",
            "config_patches": [],
            "confidence": 0.0,
        }

    try:
        import asyncio
        from anthropic import Anthropic

        client = Anthropic(api_key=ANTHROPIC_API_KEY)

        prompt = _build_prompt(passed_features, blocked_features, shap_results, current_config)

        # 동기 Anthropic 클라이언트를 스레드풀에서 실행 (이벤트 루프 블로킹 방지)
        def _call_claude():
            return client.messages.create(
                model="claude-sonnet-4-20250514",
                max_tokens=4096,
                messages=[{"role": "user", "content": prompt}],
            )

        loop = asyncio.get_event_loop()
        message = await loop.run_in_executor(None, _call_claude)

        response_text = message.content[0].text
        return _parse_response(response_text)

    except Exception as e:
        logger.error(f"LLM 분석 실패: {e}")
        return {
            "analysis": f"LLM analysis failed: {str(e)}",
            "root_cause": "error",
            "config_patches": [],
            "confidence": 0.0,
        }


def _build_prompt(
    passed_features: Dict[str, Any],
    blocked_features: Dict[str, Any],
    shap_results: Dict[str, Any],
    current_config: Dict[str, Any],
) -> str:
    """Claude에게 전달할 프롬프트 구성"""
    return f"""You are an expert in web bot detection and browser fingerprinting.
Analyze the following data and suggest specific config changes to fix blocked devices.

## Context
- 1500+ Android devices running automated Naver Shopping traffic
- Some devices (older: S10, Galaxy S6 etc) get HTTP 418 blocked
- Newer devices (S24 etc) pass without issues
- Server manages config files (headers.json, user_agents.json) that devices pull hourly

## SHAP Analysis (Top Feature Differences)
The ML model identified these features as most important for distinguishing passed vs blocked:
{json.dumps(shap_results.get('top_differences', []), indent=2, ensure_ascii=False)}

## Passed Device Features (average)
{json.dumps(passed_features, indent=2, ensure_ascii=False)}

## Blocked Device Features (average)
{json.dumps(blocked_features, indent=2, ensure_ascii=False)}

## Current Server Config
### headers.json
{json.dumps(current_config.get('headers', {}), indent=2, ensure_ascii=False)}

### user_agents.json
{json.dumps(current_config.get('user_agents', {}), indent=2, ensure_ascii=False)}

## Known Naver Bot Detection Patterns
- sec-ch-ua-platform-version mismatch with actual Android version
- Outdated Chrome version (EOL versions like 119)
- Missing zstd in accept-encoding (modern Chrome includes it)
- Missing priority header (HTTP/2+ feature)
- TLS 1.2 vs 1.3 differences
- Inconsistent User-Agent (Chrome version vs sec-ch-ua version mismatch)

## Instructions
Respond ONLY with a valid JSON object (no markdown, no explanation outside JSON):
{{
    "analysis": "2-3 sentence analysis of why devices are being blocked",
    "root_cause": "one-line root cause summary",
    "config_patches": [
        {{
            "file": "headers.json or user_agents.json",
            "key": "dot-separated path to the value (e.g., chrome_119.sec-ch-ua-platform-version)",
            "current": "current value",
            "suggested": "new value",
            "reason": "why this change helps"
        }}
    ],
    "confidence": 0.0 to 1.0
}}
"""


def _parse_response(response_text: str) -> Dict[str, Any]:
    """Claude 응답 JSON 파싱"""
    # JSON 블록 추출 (```json ... ``` 또는 순수 JSON)
    text = response_text.strip()

    # markdown 코드 블록 제거
    if "```json" in text:
        start = text.index("```json") + 7
        end = text.index("```", start)
        text = text[start:end].strip()
    elif "```" in text:
        start = text.index("```") + 3
        end = text.index("```", start)
        text = text[start:end].strip()

    try:
        result = json.loads(text)
        # 필수 필드 검증
        if "config_patches" not in result:
            result["config_patches"] = []
        if "confidence" not in result:
            result["confidence"] = 0.5
        if "analysis" not in result:
            result["analysis"] = "Analysis completed"
        if "root_cause" not in result:
            result["root_cause"] = "unknown"
        return result
    except json.JSONDecodeError as e:
        logger.error(f"LLM 응답 JSON 파싱 실패: {e}\n원문: {response_text[:500]}")
        return {
            "analysis": response_text[:500],
            "root_cause": "parse_error",
            "config_patches": [],
            "confidence": 0.0,
        }
