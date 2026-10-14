#!/usr/bin/env python3
"""下载并校验安卓棋类引擎与模型。

- Stockfish：官方 sf_19 Android arm64 版本，固定 SHA-256 校验。
- KataGo：模型取自官方归档（g170 CC0 许可）；引擎本体由
  .github/workflows/native-engines.yml 从源码构建后传入 --katago-lib。
"""

import argparse
import hashlib
import pathlib
import shutil
import subprocess
import sys
import tarfile
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
JNI = ROOT / "app" / "src" / "main" / "jniLibs" / "arm64-v8a"
ASSETS = ROOT / "app" / "src" / "main" / "assets" / "engines"
DOWNLOADS = ROOT / "downloads"

STOCKFISH_URL = (
    "https://github.com/official-stockfish/Stockfish/releases/download/sf_19/"
    "stockfish-android-arm64-universal.tar.gz"
)
STOCKFISH_SHA256 = "ebb24051aa4a222b4daaf049b882ecf1163d370c128fe02316602643f4d5e426"
KATAGO_MODEL_URL = (
    "https://katagoarchive.org/g170/neuralnets/g170e-b10c128-s1141046784-d204142634.bin.gz"
)


def download(url: str, target: pathlib.Path) -> pathlib.Path:
    if target.exists() and target.stat().st_size > 0:
        print(f"使用已下载: {target.name} ({target.stat().st_size} bytes)")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    part = target.with_name(target.name + ".part")
    print(f"下载 {url}")
    if shutil.which("curl"):
        subprocess.run(
            ["curl", "-fL", "--retry", "6", "--retry-delay", "3", "--connect-timeout", "20",
             "-C", "-", "-o", str(part), url],
            check=True,
        )
        if part != target:
            part.replace(target)
    else:
        with urllib.request.urlopen(url, timeout=300) as response, part.open("wb") as out:
            shutil.copyfileobj(response, out)
        part.replace(target)
    print(f"完成: {target.name} ({target.stat().st_size} bytes)")
    return target


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def elf_report(path: pathlib.Path) -> None:
    readelf = shutil.which("readelf") or shutil.which("llvm-readelf")
    if not readelf:
        print(f"跳过 ELF 检查（无 readelf）：{path.name}")
        return
    header = subprocess.run([readelf, "-h", str(path)], capture_output=True, text=True).stdout
    kind = next((line.split(":", 1)[1].strip() for line in header.splitlines() if "Type:" in line), "?")
    machine = next((line.split(":", 1)[1].strip() for line in header.splitlines() if "Machine:" in line), "?")
    loads = subprocess.run([readelf, "-lW", str(path)], capture_output=True, text=True).stdout
    aligns = sorted({line.split()[-1] for line in loads.splitlines() if line.strip().startswith("LOAD")})
    print(f"ELF {path.name}: {machine} / {kind} / LOAD align: {', '.join(aligns)}")
    if aligns and not all(value in ("0x1000", "0x4000") for value in aligns):
        print("  提醒：存在非 4K/16K 对齐段，16KB 页设备可能无法加载该库。")


def install_stockfish() -> None:
    archive = DOWNLOADS / STOCKFISH_URL.rsplit("/", 1)[-1]
    if archive.exists():
        actual = sha256(archive)
        if actual != STOCKFISH_SHA256:
            print(f"已存在文件校验不符，重新下载（当前 {archive.stat().st_size} bytes）")
            archive.rename(archive.with_name(archive.name + ".part"))
    archive = download(STOCKFISH_URL, archive)
    actual = sha256(archive)
    if actual != STOCKFISH_SHA256:
        raise SystemExit(f"Stockfish 校验失败: 期望 {STOCKFISH_SHA256}，实际 {actual}")
    print("Stockfish SHA-256 校验通过")
    with tarfile.open(archive) as tar:
        member = next((m for m in tar.getmembers() if m.name.endswith("stockfish-android-arm64-universal")), None)
        if member is None:
            raise SystemExit("压缩包中未找到 stockfish-android-arm64-universal")
        extracted = tar.extractfile(member)
        assert extracted is not None
        JNI.mkdir(parents=True, exist_ok=True)
        target = JNI / "libstockfish.so"
        with target.open("wb") as out:
            shutil.copyfileobj(extracted, out)
    target.chmod(0o755)
    print(f"Stockfish 就绪: {target} ({target.stat().st_size} bytes)")
    elf_report(target)


def install_katago_model() -> None:
    model = download(KATAGO_MODEL_URL, DOWNLOADS / "katago-model.bin.gz")
    digest = sha256(model)
    ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(model, ASSETS / "model.bin.gz")
    (ROOT / "engines" / "katago" / "model.sha256").write_text(
        f"{digest}  g170e-b10c128-s1141046784-d204142634.bin.gz  {KATAGO_MODEL_URL}\n",
        encoding="utf-8",
    )
    print(f"KataGo 模型就绪: model.bin.gz，SHA-256 {digest}")
    config = (ROOT / "engines" / "katago" / "gtp.cfg").read_text(encoding="utf-8")
    config = config.replace("numSearchThreads = 6", "numSearchThreads = 2")
    (ASSETS / "gtp.cfg").write_text(config, encoding="utf-8")
    print("KataGo GTP 配置就绪: gtp.cfg（numSearchThreads=2）")


def install_katago_lib(source: pathlib.Path) -> None:
    if not source.is_file():
        raise SystemExit(f"找不到 KataGo 库文件: {source}")
    JNI.mkdir(parents=True, exist_ok=True)
    target = JNI / "libkatago.so"
    shutil.copyfile(source, target)
    target.chmod(0o755)
    print(f"KataGo 引擎就绪: {target} ({target.stat().st_size} bytes)")
    elf_report(target)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--katago-lib", type=pathlib.Path, help="native-engines 工作流产出的 libkatago.so 路径")
    parser.add_argument("--skip-stockfish", action="store_true")
    parser.add_argument("--skip-model", action="store_true")
    args = parser.parse_args()

    if not args.skip_stockfish:
        install_stockfish()
    if not args.skip_model:
        install_katago_model()
    if args.katago_lib:
        install_katago_lib(args.katago_lib)
    else:
        print("提示：KataGo 引擎本体需从 native-engines 工作流下载后以 --katago-lib 传入")


if __name__ == "__main__":
    main()
