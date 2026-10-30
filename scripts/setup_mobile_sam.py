"""Fetch the pinned official MobileSAM source and weights into private storage."""
import hashlib
import json
from pathlib import Path
from urllib.request import Request, urlopen

REVISION = "f706ad9c4eb7f219c00d9050e46328518ffb65d2"
ROOT = Path(__file__).resolve().parents[1] / "data/private/mobile-sam"


def fetch(url, limit):
    with urlopen(Request(url, headers={"User-Agent": "Foodify-research"}), timeout=120) as response:
        data = response.read(limit + 1)
    if len(data) > limit:
        raise ValueError("다운로드 크기 제한을 초과했습니다.")
    return data


def main():
    ROOT.mkdir(parents=True, exist_ok=True)
    tree = json.loads(fetch(f"https://api.github.com/repos/ChaoningZhang/MobileSAM/git/trees/{REVISION}?recursive=1", 2_000_000))
    entries = [e for e in tree["tree"] if e["type"] == "blob" and (
        e["path"].startswith("mobile_sam/") and e["path"].endswith(".py")
        or e["path"] in ("LICENSE", "weights/mobile_sam.pt"))]
    records = {}
    for entry in entries:
        name = entry["path"]
        target = (ROOT / "source" / name).resolve()
        if not target.is_relative_to((ROOT / "source").resolve()):
            raise ValueError("잘못된 소스 경로입니다.")
        def valid(data):
            return len(data) == entry["size"] and hashlib.sha1(
                f"blob {len(data)}\0".encode() + data).hexdigest() == entry["sha"]
        data = target.read_bytes() if target.exists() else b""
        if not valid(data):
            data = fetch(f"https://raw.githubusercontent.com/ChaoningZhang/MobileSAM/{REVISION}/{name}", 50_000_000)
            if not valid(data):
                raise ValueError(f"원본 파일 검증 실패: {name}")
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        records[name] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
        print(name)
    if "weights/mobile_sam.pt" not in records:
        raise ValueError("모델 파일을 찾지 못했습니다.")
    (ROOT / "manifest.json").write_text(json.dumps({
        "repository": "ChaoningZhang/MobileSAM", "revision": REVISION, "files": records,
    }, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
