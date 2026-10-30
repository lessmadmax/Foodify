"""Pull only a selected debug AR session, preserving binary data on Windows."""
import argparse
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile
import tempfile


def main():
    parser = argparse.ArgumentParser(description="S23의 Foodify AR 실험 세션 가져오기")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--session", help="가져올 UUID. 생략하면 세션 목록 표시")
    parser.add_argument("--output", type=Path, default=Path("data/private/ar"))
    args = parser.parse_args()
    command = [args.adb] + (["-s", args.serial] if args.serial else [])
    try:
        if not args.session:
            subprocess.run(command + ["shell", "run-as", "com.example.foodify", "ls", "files/ar-diagnostics"], check=True, timeout=30)
            return
        if not re.fullmatch(r"[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}", args.session):
            raise ValueError("유효한 세션 UUID가 필요합니다.")
        destination = args.output.resolve() / args.session
        if destination.exists():
            raise ValueError("출력 세션이 이미 있습니다. 다른 출력 폴더를 지정하세요.")
        # Spooled temporary file avoids PowerShell's binary redirection encoding.
        with tempfile.TemporaryFile() as archive:
            subprocess.run(command + ["exec-out", "run-as", "com.example.foodify", "tar", "-cf", "-",
                                      "files/ar-diagnostics/" + args.session], stdout=archive, check=True, timeout=120)
            archive.seek(0)
            with tarfile.open(fileobj=archive) as tar:
                entries = []
                total = 0
                prefix = ("files", "ar-diagnostics", args.session)
                for member in tar.getmembers():
                    parts = PurePosixPath(member.name).parts
                    if parts[:3] != prefix or ".." in parts or "\\" in member.name or ":" in member.name:
                        raise ValueError("허용 범위 밖의 압축 경로입니다.")
                    if not (member.isdir() or member.isfile()):
                        raise ValueError("일반 파일과 폴더만 가져올 수 있습니다.")
                    total += member.size
                    if total > 210 * 1024 * 1024:
                        raise ValueError("실험 자료 용량 제한을 초과했습니다.")
                    target = destination.joinpath(*parts[3:]).resolve()
                    if not target.is_relative_to(destination):
                        raise ValueError("잘못된 출력 경로입니다.")
                    entries.append((member, target))
                if not entries:
                    raise ValueError("세션 파일이 없습니다.")
                for member, target in entries:
                    if member.isdir():
                        target.mkdir(parents=True, exist_ok=True)
                    else:
                        target.parent.mkdir(parents=True, exist_ok=True)
                        with tar.extractfile(member) as source, target.open("xb") as output:
                            while block := source.read(1024 * 1024):
                                output.write(block)
        print(destination)
    except (ValueError, OSError, subprocess.SubprocessError, tarfile.TarError) as error:
        parser.exit(2, f"가져오기 실패: {error}\n")


if __name__ == "__main__":
    main()
