"""
Config 자동 패치 모듈 — LLM 제안을 실제 config 파일에 적용

디바이스는 1시간마다 GET /config/full 으로 자동 pull하므로
config 파일 수정 → 자동 배포.
"""
import json
import logging
import os
import copy
from typing import Dict, Any, List, Optional
from datetime import datetime

logger = logging.getLogger(__name__)

CONFIG_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "config")

# 허용된 config 파일 목록 (path traversal 방지)
ALLOWED_CONFIG_FILES = {"headers.json", "user_agents.json", "webview_config.json", "webview_settings.json"}


def _validate_config_filename(filename: str) -> str:
    """config 파일명 검증 — path traversal 방지"""
    # 경로 구분자 및 상위 디렉토리 참조 차단
    if ".." in filename or "/" in filename or "\\" in filename or os.sep in filename:
        raise ValueError(f"Invalid config filename: {filename}")
    if filename not in ALLOWED_CONFIG_FILES:
        raise ValueError(f"Config file not in allowlist: {filename}. Allowed: {ALLOWED_CONFIG_FILES}")
    filepath = os.path.join(CONFIG_DIR, filename)
    # 절대 경로가 CONFIG_DIR 내에 있는지 최종 확인
    if not os.path.abspath(filepath).startswith(os.path.abspath(CONFIG_DIR) + os.sep):
        raise ValueError(f"Path traversal detected: {filename}")
    return filepath


def load_config(filename: str) -> Dict[str, Any]:
    """config 파일 로드 (allowlist 검증)"""
    filepath = _validate_config_filename(filename)
    with open(filepath, "r", encoding="utf-8") as f:
        return json.load(f)


def save_config(filename: str, data: Dict[str, Any]) -> None:
    """config 파일 저장 (allowlist 검증)"""
    filepath = _validate_config_filename(filename)
    with open(filepath, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
        f.write("\n")


def get_nested_value(data: Dict, key_path: str) -> Any:
    """dot-separated 키로 중첩 딕셔너리 값 조회"""
    keys = key_path.split(".")
    current = data
    for key in keys:
        if isinstance(current, dict) and key in current:
            current = current[key]
        elif isinstance(current, list):
            try:
                idx = int(key)
                current = current[idx]
            except (ValueError, IndexError):
                return None
        else:
            return None
    return current


def set_nested_value(data: Dict, key_path: str, value: Any) -> bool:
    """dot-separated 키로 중첩 딕셔너리 값 설정"""
    keys = key_path.split(".")
    current = data
    for key in keys[:-1]:
        if isinstance(current, dict):
            if key not in current:
                current[key] = {}
            current = current[key]
        elif isinstance(current, list):
            try:
                idx = int(key)
                current = current[idx]
            except (ValueError, IndexError):
                return False
        else:
            return False

    last_key = keys[-1]
    if isinstance(current, dict):
        current[last_key] = value
        return True
    elif isinstance(current, list):
        try:
            idx = int(last_key)
            current[idx] = value
            return True
        except (ValueError, IndexError):
            return False
    return False


def apply_config_patch(
    patches: List[Dict[str, Any]],
    dry_run: bool = True,
) -> Dict[str, Any]:
    """
    LLM 제안을 실제 config 파일에 적용.

    1. 현재 config 백업 (before_value 저장)
    2. JSON 파일 수정
    3. 변경 검증 (JSON 유효성)
    4. dry_run=False 일 때만 실제 적용

    Args:
        patches: LLM이 제안한 config_patches 목록
        dry_run: True면 프리뷰만, False면 실제 적용

    Returns:
        {
            "applied": [...],   # 적용 성공 목록
            "skipped": [...],   # 건너뛴 목록
            "errors": [...],    # 에러 목록
            "dry_run": bool,
        }
    """
    results = {
        "applied": [],
        "skipped": [],
        "errors": [],
        "dry_run": dry_run,
    }

    # config 파일별로 그룹핑
    config_groups: Dict[str, List[Dict]] = {}
    for patch in patches:
        filename = patch.get("file", "")
        if filename not in config_groups:
            config_groups[filename] = []
        config_groups[filename].append(patch)

    for filename, file_patches in config_groups.items():
        try:
            config_data = load_config(filename)
            original_data = copy.deepcopy(config_data)

            for patch in file_patches:
                key_path = patch.get("key", "")
                suggested = patch.get("suggested", "")
                reason = patch.get("reason", "")

                if not key_path:
                    results["skipped"].append({
                        "file": filename,
                        "reason": "empty key path",
                    })
                    continue

                # 현재 값 조회
                current_value = get_nested_value(config_data, key_path)
                before_value = current_value

                # 값 변환 (문자열로 전달된 경우)
                new_value = suggested
                if isinstance(current_value, (int, float)) and isinstance(suggested, str):
                    try:
                        new_value = type(current_value)(suggested)
                    except (ValueError, TypeError):
                        pass

                # 같은 값이면 스킵
                if str(current_value) == str(new_value):
                    results["skipped"].append({
                        "file": filename,
                        "key": key_path,
                        "reason": "value unchanged",
                        "current": str(current_value),
                    })
                    continue

                # 값 설정
                success = set_nested_value(config_data, key_path, new_value)
                if not success:
                    results["errors"].append({
                        "file": filename,
                        "key": key_path,
                        "error": "failed to set value (key path not found)",
                    })
                    continue

                results["applied"].append({
                    "file": filename,
                    "key": key_path,
                    "before": str(before_value) if before_value is not None else "null",
                    "after": str(new_value),
                    "reason": reason,
                })

            # JSON 유효성 검증
            try:
                json.dumps(config_data, ensure_ascii=False)
            except (TypeError, ValueError) as e:
                results["errors"].append({
                    "file": filename,
                    "error": f"JSON validation failed: {e}",
                })
                continue

            # 실제 적용
            if not dry_run and results["applied"]:
                save_config(filename, config_data)
                logger.info(f"Config 적용 완료: {filename} ({len(file_patches)}건)")

        except FileNotFoundError:
            results["errors"].append({
                "file": filename,
                "error": "config file not found",
            })
        except json.JSONDecodeError as e:
            results["errors"].append({
                "file": filename,
                "error": f"invalid JSON: {e}",
            })
        except Exception as e:
            results["errors"].append({
                "file": filename,
                "error": str(e),
            })

    return results


def get_current_configs() -> Dict[str, Any]:
    """현재 모든 config 파일 로드"""
    configs = {}
    for filename in ["headers.json", "user_agents.json", "webview_config.json"]:
        try:
            configs[filename.replace(".json", "")] = load_config(filename)
        except Exception as e:
            logger.warning(f"Config 로드 실패: {filename}: {e}")
            configs[filename.replace(".json", "")] = {}
    return configs
