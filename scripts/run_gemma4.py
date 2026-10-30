"""Run the selected Gemma 4 E2B model offline with a bounded worker process."""
import argparse
import ctypes
import io
import hashlib
import math
import json
from pathlib import Path
import subprocess
import sys
import time

from setup_gemma4 import ROOT, NAME, SIZE

PACKAGES = ROOT/"python"
if PACKAGES.exists():
    sys.path.insert(0, str(PACKAGES))

PROMPT = (
    "사진에 보이는 음식의 이름을 한국어로 식별하세요. 접시, 그릇, 식탁, 배경은 제외하세요. "
    "각각 구별되는 음식을 하나씩 나열하세요. 추측할 수 없는 음식은 '알 수 없는 음식'으로 표시하세요. "
    '설명이나 코드 블록 없이 다음 형식의 JSON만 출력하세요: {"foods":[{"name":"음식명"}]}'
)
LOCATION_PROMPT = (
    "사진의 각 음식 이름과 음식 부분만 감싸는 사각형 위치를 찾으세요. 접시·그릇·배경은 제외하세요. "
    "bbox는 [왼쪽, 위, 오른쪽, 아래]이며 이미지 왼쪽 위가 (0,0), 오른쪽 아래가 (1,1)인 정규화 좌표입니다. "
    '설명 없이 JSON만 출력하세요: {"foods":[{"name":"음식명","bbox":[0.1,0.2,0.8,0.9]}]}'
)


def parse_foods(text, locate=False):
    text = text.strip()
    fence = chr(96)*3
    if text.startswith(fence+"json") and text.endswith(fence):
        text = text[7:-3].strip()
    elif text.startswith(fence) and text.endswith(fence):
        text = text[3:-3].strip()
    if len(text) > 16000:
        raise ValueError("모델 응답 길이 초과")
    data = json.loads(text)
    if not isinstance(data, dict) or set(data) != {"foods"}:
        raise ValueError("foods 배열만 포함하는 JSON 객체가 필요합니다.")
    foods = data["foods"]
    if not isinstance(foods, list) or len(foods) > 30:
        raise ValueError("음식 목록은 최대 30개인 배열이어야 합니다.")
    for item in foods:
        if (not isinstance(item, dict) or set(item) != ({"name", "bbox"} if locate else {"name"})
                or not isinstance(item["name"], str) or not item["name"].strip()
                or len(item["name"]) > 120):
            raise ValueError("음식 이름 형식을 확인하세요.")
        item["name"] = item["name"].strip()
        if locate:
            box = item["bbox"]
            if (not isinstance(box, list) or len(box) != 4
                    or any(type(v) not in (int, float) or not math.isfinite(v) or not 0 <= v <= 1 for v in box)
                    or box[0] >= box[2] or box[1] >= box[3]):
                raise ValueError("음식 위치는 0~1 범위의 유효한 사각형이어야 합니다.")
    return foods


def peak_working_set_mb():
    if sys.platform != "win32":
        return None
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in (
                "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    kernel = ctypes.WinDLL("kernel32")
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    psapi = ctypes.WinDLL("psapi")
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    ok = psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(), ctypes.byref(counters), counters.cb)
    return round(counters.PeakWorkingSetSize/(1024*1024), 2) if ok else None


def save(report, output):
    temporary = output.with_suffix(output.suffix+".writing")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")
    temporary.replace(output)


def worker(args):
    prompt = LOCATION_PROMPT if args.locate else PROMPT
    report = {"model": "google/gemma-4-E2B-it", "runtime": "litert-lm-api 0.18.1",
              "modelFormat": "LiteRT-LM", "backend": args.backend, "visionBackend": args.vision_backend,
              "imagePath": str(args.image), "prompt": prompt, "task": "locate" if args.locate else "names",
              "maxOutputTokens": args.max_tokens,
              "status": "STARTING", "runs": [], "startupSeconds": None}
    save(report, args.output)
    try:
        import litert_lm
        from PIL import Image, ImageOps
        litert_lm.set_min_log_severity(litert_lm.LogSeverity.ERROR)
        with Image.open(args.image) as original:
            photo = ImageOps.exif_transpose(original).convert("RGB")
            report["imageFingerprint"] = "image-only:" + hashlib.sha256(photo.tobytes()).hexdigest()
            report["originalImageSize"] = list(photo.size)
            photo.thumbnail((512, 512))
            encoded = io.BytesIO()
            photo.save(encoded, format="JPEG", quality=90)
        report["imageSize"] = list(photo.size)
        backend = litert_lm.Backend.CPU() if args.backend == "cpu" else litert_lm.Backend.GPU()
        vision = litert_lm.Backend.CPU() if args.vision_backend == "cpu" else litert_lm.Backend.GPU()
        (ROOT/"cache").mkdir(parents=True, exist_ok=True)
        started = time.perf_counter()
        with litert_lm.Engine(
            str(ROOT/NAME), backend=backend, vision_backend=vision,
            max_num_tokens=4096, max_num_images=1, cache_dir=str(ROOT/"cache"),
        ) as engine:
            report["startupSeconds"] = round(time.perf_counter()-started, 3)
            report["status"] = "RUNNING"
            save(report, args.output)
            print("Gemma 4 E2B ready", flush=True)
            for index in range(args.repeat):
                with engine.create_conversation(
                    max_output_tokens=args.max_tokens, automatic_tool_calling=False,
                    thinking_config=litert_lm.ThinkingConfig(enable_thinking=False, thinking_token_budget=0),
                    sampler_config=litert_lm.SamplerConfig(top_k=1, temperature=0, seed=42),
                ) as conversation:
                    tick = time.perf_counter()
                    response = conversation.send_message(litert_lm.Contents.of(
                        litert_lm.Content.ImageBytes(encoded.getvalue()), prompt))
                    seconds = round(time.perf_counter()-tick, 3)
                    text = "".join(part.get("text", "") for part in response.get("content", [])
                                   if part.get("type") == "text")
                    result = {"index": index+1, "seconds": seconds, "content": text, "schemaValid": False}
                    try:
                        result["foods"] = parse_foods(text, locate=args.locate)
                        result["schemaValid"] = True
                    except ValueError as error:
                        result["validationError"] = str(error)
                    report["runs"].append(result)
                    report["peakWorkingSetMb"] = peak_working_set_mb()
                    save(report, args.output)
                    print(f"Run {index+1}: {seconds}s, JSON valid={result['schemaValid']}", flush=True)
        report["status"] = "COMPLETED"
    except Exception as error:
        report["status"], report["error"] = "FAILED", str(error)
        raise
    finally:
        report["peakWorkingSetMb"] = peak_working_set_mb()
        save(report, args.output)


def main():
    # PowerShell tool output and saved diagnostic logs use UTF-8.
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="Gemma 4 E2B PC 음식 인식 시험")
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--locate", action="store_true", help="음식명과 위치 사각형을 함께 추정")
    parser.add_argument("--max-tokens", type=int, default=256)
    parser.add_argument("--timeout", type=int, default=300)
    parser.add_argument("--backend", choices=["cpu", "gpu"], default="cpu")
    parser.add_argument("--vision-backend", choices=["cpu", "gpu"], default="cpu")
    parser.add_argument("--worker", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.image.is_file():
        parser.error("사진 파일이 필요합니다.")
    if not 1 <= args.repeat <= 10 or not 16 <= args.max_tokens <= 1024 or not 30 <= args.timeout <= 1800:
        parser.error("repeat 1~10, max-tokens 16~1024, timeout 30~1800 범위를 사용하세요.")
    if not (ROOT/NAME).is_file() or (ROOT/NAME).stat().st_size != SIZE:
        parser.error("먼저 scripts/setup_gemma4.py의 다운로드·해시 검증을 완료하세요.")
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.worker:
        worker(args)
        return
    command = [sys.executable, str(Path(__file__).resolve()), "--worker",
               "--image", str(args.image.resolve()), "--output", str(args.output),
               "--repeat", str(args.repeat), "--max-tokens", str(args.max_tokens),
               "--backend", args.backend, "--vision-backend", args.vision_backend]
    if args.locate:
        command.append("--locate")
    log_path = args.output.with_suffix(".runtime.log")
    save({"model": "google/gemma-4-E2B-it", "status": "STARTING", "runs": []}, args.output)
    with log_path.open("wb") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            code = process.wait(timeout=args.timeout)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
            report = json.loads(args.output.read_text(encoding="utf-8")) if args.output.exists() else {}
            report.update(status="TIMEOUT", error=f"실행 제한 {args.timeout}초 초과")
            save(report, args.output)
            parser.exit(2, f"시간 초과. 결과: {args.output}; 로그: {log_path}\n")
        if code:
            report = json.loads(args.output.read_text(encoding="utf-8")) if args.output.exists() else {}
            report.update(status="FAILED", workerExitCode=code)
            save(report, args.output)
            parser.exit(2, f"실행 실패. 결과: {args.output}; 로그: {log_path}\n")
    report = json.loads(args.output.read_text(encoding="utf-8"))
    print(json.dumps({"status": report["status"], "runs": report["runs"],
                      "peakWorkingSetMb": report.get("peakWorkingSetMb")}, ensure_ascii=False))
    print(args.output)


if __name__ == "__main__":
    main()
