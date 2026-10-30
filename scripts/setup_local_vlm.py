"""Download pinned official CPU runtime and a small VLM for PC feasibility tests."""
import hashlib
import json
from pathlib import Path
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1] / "data/private/local-vlm"
REVISION = "72e986006ef53e37cdd3f6d4241c90b0f01df376"
FILES = [
    ("llama-b11946-bin-win-cpu-x64.zip",
     "https://github.com/ggml-org/llama.cpp/releases/download/b11946/llama-b11946-bin-win-cpu-x64.zip",
     "8a702bb07c873b133c56eb3acd92a48d7c5c9ad339c6435eddc5185e46e1a043"),
    ("SmolVLM-500M-Instruct-Q8_0.gguf",
     f"https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/{REVISION}/SmolVLM-500M-Instruct-Q8_0.gguf",
     "9d4612de6a42214499e301494a3ecc2be0abdd9de44e663bda63f1152fad1bf4"),
    ("mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
     f"https://huggingface.co/ggml-org/SmolVLM-500M-Instruct-GGUF/resolve/{REVISION}/mmproj-SmolVLM-500M-Instruct-Q8_0.gguf",
     "d1eb8b6b23979205fdf63703ed10f788131a3f812c7b1f72e0119d5d81295150"),
]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(4 * 1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    for name, url, expected in FILES:
        target = ROOT / name
        if target.exists() and sha256(target) == expected:
            print(f"Verified existing {name}", flush=True)
            continue
        partial = target.with_suffix(target.suffix + ".partial")
        print(f"Downloading {name}", flush=True)
        request = urllib.request.Request(url, headers={"User-Agent": "Foodify-local-vlm-evaluation"})
        with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as destination:
            total = 0
            next_notice = 50 * 1024 * 1024
            while block := response.read(4 * 1024 * 1024):
                destination.write(block)
                total += len(block)
                if total >= next_notice:
                    print(f"{name}: {total // (1024*1024)} MiB", flush=True)
                    next_notice += 50 * 1024 * 1024
        if sha256(partial) != expected:
            raise ValueError(f"SHA256 mismatch: {name}")
        partial.replace(target)
    runtime = ROOT / "runtime"
    runtime.mkdir(exist_ok=True)
    with zipfile.ZipFile(ROOT / FILES[0][0]) as archive:
        for item in archive.infolist():
            target = (runtime / item.filename).resolve()
            if not target.is_relative_to(runtime.resolve()):
                raise ValueError("Invalid runtime archive path")
        archive.extractall(runtime)
    (ROOT/"manifest.json").write_text(json.dumps({
        "model": "HuggingFaceTB/SmolVLM-500M-Instruct", "ggufRevision": REVISION,
        "runtime": "ggml-org/llama.cpp b11946 Windows CPU x64",
        "purpose": "PC feasibility baseline, final Foodify model not selected",
        "files": [{"name": n, "url": u, "sha256": h} for n,u,h in FILES],
    }, indent=2), encoding="utf-8")
    print("Local VLM assets ready:", ROOT, flush=True)


if __name__ == "__main__":
    main()
