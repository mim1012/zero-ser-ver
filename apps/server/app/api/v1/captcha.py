"""
CAPTCHA 해결 API — Claude Vision으로 영수증 캡챠 풀기
"""
from fastapi import APIRouter
from pydantic import BaseModel
import anthropic
import os
import logging
import base64

logger = logging.getLogger(__name__)
router = APIRouter()

# Anthropic 클라이언트 (ANTHROPIC_API_KEY 환경변수 필요)
client = None


def get_client():
    global client
    if client is None:
        api_key = os.getenv("ANTHROPIC_API_KEY")
        if not api_key:
            logger.error("ANTHROPIC_API_KEY 미설정")
            return None
        client = anthropic.Anthropic(api_key=api_key)
    return client


class CaptchaSolveRequest(BaseModel):
    device_id: str
    image_base64: str
    question: str


class CaptchaSolveResponse(BaseModel):
    answer: str
    confidence: str


@router.get("/status")
async def captcha_status():
    """캡챠 서비스 상태 확인"""
    api_key = os.getenv("ANTHROPIC_API_KEY", "")
    return {
        "api_key_set": bool(api_key),
        "api_key_prefix": api_key[:12] + "..." if len(api_key) > 12 else "(empty)",
    }


@router.post("/solve", response_model=CaptchaSolveResponse)
async def solve_captcha(req: CaptchaSolveRequest):
    """영수증 캡챠 이미지 + 질문 → Claude Vision으로 답 추출"""

    if not req.image_base64 or not req.question:
        logger.warning("CAPTCHA: 이미지 또는 질문 없음")
        return CaptchaSolveResponse(answer="", confidence="none")

    c = get_client()
    if c is None:
        logger.error("CAPTCHA: ANTHROPIC_API_KEY 미설정!")
        return CaptchaSolveResponse(answer="", confidence="none")

    try:
        # base64 이미지 유효성 체크
        image_data = req.image_base64
        if image_data.startswith("data:"):
            image_data = image_data.split(",", 1)[1]

        # Claude Vision 호출
        message = c.messages.create(
            model="claude-sonnet-4-5-20250929",
            max_tokens=100,
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image",
                            "source": {
                                "type": "base64",
                                "media_type": "image/png",
                                "data": image_data,
                            },
                        },
                        {
                            "type": "text",
                            "text": (
                                f"이 영수증 이미지를 보고 다음 질문에 답하세요.\n\n"
                                f"질문: {req.question}\n\n"
                                f"규칙:\n"
                                f"- 답만 정확히 작성 (설명 없이)\n"
                                f"- 빈칸 [?]에 들어갈 값만 답하세요\n"
                                f"- 숫자면 숫자만, 글자면 글자만\n"
                                f"- 예: '123' 또는 '가나다'\n\n"
                                f"답:"
                            ),
                        },
                    ],
                }
            ],
        )

        answer = message.content[0].text.strip()
        # 따옴표, 마침표 등 제거
        answer = answer.strip("'\".,;:!? ")

        confidence = "high" if len(answer) > 0 else "none"
        logger.info(f"CAPTCHA solved: q={req.question} → a={answer} ({confidence})")

        return CaptchaSolveResponse(answer=answer, confidence=confidence)

    except Exception as e:
        logger.error(f"CAPTCHA solve error: {e}")
        return CaptchaSolveResponse(answer="", confidence="none")
