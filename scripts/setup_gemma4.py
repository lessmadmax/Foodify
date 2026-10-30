"""Download Foodify's selected Gemma 4 E2B LiteRT model, pinned and verified."""
import hashlib
import json
from pathlib import Path
import urllib.request

ROOT = Path(__file__).resolve().parents[1] / "data/private/gemma4"
REVISION = "1caa527c1e88b55f046fcd8d2b4265606680663d"
NAME = "gemma-4-E2B-it.litertlm"
SIZE = 2588147712
SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
URL = f"https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/{REVISION}/{NAME}"


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(4*1024*1024):
            result.update(block)
    return result.hexdigest()


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    target = ROOT/NAME
    if target.exists() and target.stat().st_size == SIZE and digest(target) == SHA256:
        print("Verified existing Gemma 4 E2B", flush=True)
    else:
        partial = target.with_suffix(target.suffix+".partial")
        downloaded = partial.stat().st_size if partial.exists() else 0
        if downloaded > SIZE:
            raise ValueError("Partial model exceeds expected size")
        if downloaded < SIZE:
            headers = {"User-Agent": "Foodify-Gemma4-setup"}
            if downloaded:
                headers["Range"] = f"bytes={downloaded}-"
            with urllib.request.urlopen(urllib.request.Request(URL, headers=headers), timeout=60) as response:
                if downloaded and response.status != 206:
                    downloaded = 0
                elif downloaded and not response.headers.get("Content-Range", "").startswith(f"bytes {downloaded}-"):
                    raise ValueError("Unexpected download resume range")
                with partial.open("ab" if downloaded else "wb") as output:
                    notice = downloaded + 100*1024*1024
                    while block := response.read(4*1024*1024):
                        output.write(block)
                        downloaded += len(block)
                        if downloaded > SIZE:
                            raise ValueError("Downloaded model exceeds expected size")
                        if downloaded >= notice:
                            print(f"Gemma 4 E2B: {downloaded//(1024*1024)} MiB / {SIZE//(1024*1024)} MiB", flush=True)
                            notice += 100*1024*1024
        if partial.stat().st_size != SIZE or digest(partial) != SHA256:
            raise ValueError("Gemma model size/SHA256 mismatch")
        partial.replace(target)
        print("Gemma 4 E2B SHA256 verified", flush=True)
    (ROOT/"manifest.json").write_text(json.dumps({
        "model": "google/gemma-4-E2B-it", "format": "LiteRT-LM",
        "repository": "litert-community/gemma-4-E2B-it-litert-lm", "revision": REVISION,
        "filename": NAME, "size": SIZE, "sha256": SHA256, "url": URL,
        "selection": "Foodify selected local multimodal model",
        "runtimeVersion": "litert-lm-api==0.18.1",
        "androidValidation": "pending Galaxy S23 tests",
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(target, flush=True)


if __name__ == "__main__":
    main()
