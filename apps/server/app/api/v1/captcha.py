"""
CAPTCHA 해결 API — Claude Vision으로 영수증 캡챠 풀이
"""
import os
import time
import logging
import httpx
from fastapi import APIRouter
from pydantic import BaseModel, field_validator

logger = logging.getLogger(__name__)
router = APIRouter()

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "").strip()


class CaptchaSolveRequest(BaseModel):
    device_id: str = ""
    image_base64: str
    question: str

    @field_validator("image_base64")
    @classmethod
    def validate_image_size(cls, v: str) -> str:
        max_size = 10 * 1024 * 1024  # 10MB
        if len(v) > max_size:
            raise ValueError(f"image_base64 exceeds {max_size} bytes")
        return v


class CaptchaSolveResponse(BaseModel):
    answer: str
    confidence: str = "medium"
    error: str = ""


@router.get("/status")
async def captcha_status():
    """캡챠 서비스 상태 확인"""
    return {
        "api_key_set": bool(ANTHROPIC_API_KEY),
        "api_key_prefix": ANTHROPIC_API_KEY[:12] + "..." if len(ANTHROPIC_API_KEY) > 12 else "(empty)",
    }


@router.post("/solve", response_model=CaptchaSolveResponse)
async def solve_captcha(req: CaptchaSolveRequest):
    """영수증 캡챠 해결 — Claude Vision 직접 HTTP 호출"""
    start = time.time()
    logger.info(f"CAPTCHA request: device={req.device_id} question={req.question[:80]}")

    if not ANTHROPIC_API_KEY:
        return CaptchaSolveResponse(answer="", confidence="none", error="ANTHROPIC_API_KEY not set")

    if not req.image_base64 or not req.question:
        return CaptchaSolveResponse(answer="", confidence="none", error="empty image or question")

    try:
        image_data = req.image_base64
        media_type = "image/png"
        if image_data.startswith("data:"):
            header, _, image_data = image_data.partition(",")
            if "jpeg" in header or "jpg" in header:
                media_type = "image/jpeg"
            elif "webp" in header:
                media_type = "image/webp"
        elif image_data.startswith("/9j/"):
            media_type = "image/jpeg"

        # Anthropic API 직접 호출 (httpx)
        async with httpx.AsyncClient(timeout=30.0) as client:
            resp = await client.post(
                "https://api.anthropic.com/v1/messages",
                headers={
                    "x-api-key": ANTHROPIC_API_KEY,
                    "anthropic-version": "2023-06-01",
                    "content-type": "application/json",
                },
                json={
                    "model": "claude-haiku-4-5-20251001",
                    "max_tokens": 50,
                    "system": "You are an OCR assistant. Read Korean receipt images and answer questions. Reply with ONLY the answer (a single number or character). Never explain.",
                    "messages": [{
                        "role": "user",
                        "content": [
                            {
                                "type": "image",
                                "source": {
                                    "type": "base64",
                                    "media_type": media_type,
                                    "data": image_data
                                }
                            },
                            {
                                "type": "text",
                                "text": (
                                    "아래는 가상 영수증 이미지입니다.\n"
                                    "먼저 영수증의 모든 텍스트를 읽은 후,\n"
                                    f"다음 질문에 답하세요: {req.question}\n\n"
                                    "정답만 출력하세요 (숫자 1개 또는 글자 1개)."
                                )
                            }
                        ]
                    }]
                }
            )

        if resp.status_code != 200:
            error_msg = resp.text[:200]
            logger.error(f"CAPTCHA API error {resp.status_code}: {error_msg}")
            return CaptchaSolveResponse(answer="", confidence="none", error=f"API {resp.status_code}: {error_msg}")

        data = resp.json()
        answer = data["content"][0]["text"].strip()

        elapsed = time.time() - start
        logger.info(f"CAPTCHA solved: Q={req.question} A={answer} device={req.device_id} time={elapsed:.1f}s")
        return CaptchaSolveResponse(answer=answer, confidence="high")

    except Exception as e:
        elapsed = time.time() - start
        logger.error(f"CAPTCHA solve error ({elapsed:.1f}s): {e}")
        return CaptchaSolveResponse(answer="", confidence="none", error=str(e)[:200])
