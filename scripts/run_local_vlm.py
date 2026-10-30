"""Bounded, loopback-only PC image-inference benchmark; no cloud API calls."""
import argparse
import base64
import io
import json
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request

from PIL import Image, ImageOps

from setup_local_vlm import ROOT


def main():
    parser = argparse.ArgumentParser(description="PC 로컬 음식 이미지 모델 실행 시험")
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prompt", default="Identify the foods visible in this image. List the food names briefly. If uncertain, say so.")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--max-tokens", type=int, default=128)
    args = parser.parse_args()
    if not 1 <= args.repeat <= 10 or not 1 <= args.max_tokens <= 512:
        parser.error("repeat는 1~10, max-tokens는 1~512 범위입니다.")
    runtimes = list((ROOT/"runtime").rglob("llama-server.exe"))
    if len(runtimes) != 1:
        parser.error("먼저 scripts/setup_local_vlm.py를 실행하세요.")
    with Image.open(args.image) as original:
        photo = ImageOps.exif_transpose(original).convert("RGB")
        photo.thumbnail((512, 512))
        buffer = io.BytesIO()
        photo.save(buffer, format="JPEG", quality=90)
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        port = probe.getsockname()[1]
    base_url = f"http://127.0.0.1:{port}"
    # Environment proxy settings should never forward the user's image off-device.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    logs = args.output.with_suffix(".server.log")
    command = [str(runtimes[0]), "-m", str(ROOT/"SmolVLM-500M-Instruct-Q8_0.gguf"),
               "--mmproj", str(ROOT/"mmproj-SmolVLM-500M-Instruct-Q8_0.gguf"),
               "--host", "127.0.0.1", "--port", str(port), "-t", "4", "-ngl", "0",
               "--no-mmproj-offload", "-c", "4096", "-np", "1"]
    results = []
    started = time.perf_counter()
    report = {"model": "SmolVLM-500M-Instruct-Q8_0", "runtime": "llama.cpp b11946",
              "device": "PC CPU", "imagePath": str(args.image), "imageSize": list(photo.size),
              "prompt": args.prompt, "maxTokens": args.max_tokens, "startupSeconds": None, "runs": results,
              "note": "PC 실행 시험. 한국 음식 정확도와 S23 속도는 별도 평가 대상."}
    with logs.open("wb") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        try:
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("로컬 모델 시작 실패. 서버 로그를 확인하세요.")
                try:
                    with client.open(base_url+"/health", timeout=2) as response:
                        if response.status == 200:
                            break
                except (OSError, urllib.error.URLError):
                    time.sleep(.25)
            else:
                raise TimeoutError("모델 시작 시간 초과")
            report["startupSeconds"] = round(time.perf_counter()-started, 3)
            print("Local model ready", flush=True)
            for index in range(args.repeat):
                payload = {"messages": [{"role": "user", "content": [
                    {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,"+encoded}},
                    {"type": "text", "text": args.prompt}]}],
                    "temperature": 0, "seed": 42, "max_tokens": args.max_tokens, "stream": False,
                    "cache_prompt": False}
                request = urllib.request.Request(base_url+"/v1/chat/completions",
                    data=json.dumps(payload).encode(), headers={"Content-Type": "application/json"})
                tick = time.perf_counter()
                with client.open(request, timeout=180) as response:
                    answer = json.load(response)
                results.append({"index": index+1, "seconds": round(time.perf_counter()-tick, 3),
                                "content": answer["choices"][0]["message"]["content"],
                                "finishReason": answer["choices"][0].get("finish_reason"),
                                "usage": answer.get("usage"), "timings": answer.get("timings")})
                print(f"Run {index+1}: {results[-1]['seconds']} seconds", flush=True)
        except Exception as error:
            report["error"] = str(error)
            raise
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(args.output)


if __name__ == "__main__":
    main()
