"""
CAPTCHA 해결 API — Claude Vision으로 영수증 캡챠 풀이
"""
import os
import logging
from fastapi import APIRouter
from pydantic import BaseModel

logger = logging.getLogger(__name__)
router = APIRouter()

ANTHROPIC_API_KEY = os.getenv("ANTHROPIC_API_KEY", "")


class CaptchaSolveRequest(BaseModel):
    device_id: str = ""
    image_base64: str
    question: str


class CaptchaSolveResponse(BaseModel):
    answer: str
    confidence: str = "medium"


@router.post("/solve", response_model=CaptchaSolveResponse)
async def solve_captcha(req: CaptchaSolveRequest):
    """영수증 캡챠 해결 — Claude Vision 호출"""
    if not ANTHROPIC_API_KEY:
        logger.warning("ANTHROPIC_API_KEY 미설정")
        return CaptchaSolveResponse(answer="", confidence="none")

    if not req.image_base64 or not req.question:
        return CaptchaSolveResponse(answer="", confidence="none")

    try:
        from anthropic import Anthropic
        client = Anthropic(api_key=ANTHROPIC_API_KEY)

        # 이미지 미디어 타입 추론
        media_type = "image/png"
        if req.image_base64.startswith("/9j/"):
            media_type = "image/jpeg"

        response = client.messages.create(
            model="claude-sonnet-4-5-20250929",
            max_tokens=100,
            messages=[{
                "role": "user",
                "content": [
                    {
                        "type": "image",
                        "source": {
                            "type": "base64",
                            "media_type": media_type,
                            "data": req.image_base64
                        }
                    },
                    {
                        "type": "text",
                        "text": (
                            "이것은 네이버 보안 확인용 가상 영수증 이미지입니다.\n"
                            f"질문: {req.question}\n\n"
                            "영수증을 읽고 정답만 숫자 또는 글자로 답해주세요. "
                            "설명 없이 정답만 출력하세요."
                        )
                    }
                ]
            }]
        )

        answer = response.content[0].text.strip()
        logger.info(f"CAPTCHA solved: Q={req.question} A={answer} device={req.device_id}")
        return CaptchaSolveResponse(answer=answer, confidence="high")

    except Exception as e:
        logger.error(f"CAPTCHA solve error: {e}")
        return CaptchaSolveResponse(answer="", confidence="none")
