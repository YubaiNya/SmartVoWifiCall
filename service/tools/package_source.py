"""Package product source using an explicit allowlist, never internal reports/logs."""
import argparse
import hashlib
import pathlib
import subprocess
import zipfile

REPO = pathlib.Path(__file__).resolve().parents[2]


def is_product_source(name):
    path = pathlib.PurePosixPath(name)
    if path.is_absolute() or ".." in path.parts:
        return False
    if name in {"LICENSE", "README.md", ".gitignore", ".gitattributes", "engine/go.mod",
                "service/.gitignore", "service/build.gradle.kts", "service/settings.gradle.kts",
                "service/gradle.properties", "service/app/build.gradle.kts",
                "service/deps/android-system.source.json", "service/deps/mihomo.source.json", "service/deps/lpa.source.json"}:
        return True
    if any(part.startswith(".") or part in {"build", "dist", "__pycache__", "docs",
            "logs", "research", "knowledge", "agent-memory", "device-private"} for part in path.parts):
        return False
    if name.startswith("engine/"):
        return path.suffix == ".go" or ("testdata" in path.parts and path.suffix == ".json")
    if name.startswith("service/app/src/main/"):
        if name.startswith("service/app/src/main/cpp/"):
            return path.suffix in {".c", ".h", ".txt"} or path.name in {"LICENSE", "REUSE.toml"}
        return path.suffix in {".java", ".kt", ".xml", ".png"} or name == "service/app/src/main/assets/carrier_profiles.json" or name.startswith("service/app/src/main/assets/licenses/")
    if name.startswith("service/tests/"):
        return path.suffix in {".java", ".py", ".ps1", ".xml"}
    if name.startswith("service/tools/"):
        return name in {"service/tools/build.py", "service/tools/package_source.py", "service/tools/verify_artifacts.py", "service/tools/engine_iface/inspect_native.py"}
    if name.startswith("service/module/licenses/"):
        return path.suffix == ".txt" or path.name in {"LICENSE", "PATENTS", "NOTICE"}
    if name.startswith("service/module/"):
        return path.suffix in {".sh", ".xml"} or path.name in {"module.prop", "sepolicy.rule", "NOTICE-SmartVoWifiCall.txt"}
    if name.startswith("telemetry-worker/src/") or name.startswith("telemetry-worker/tests/"):
        return path.suffix in {".js", ".mjs"}
    return name == "telemetry-worker/wrangler.toml"


def package(repo, output):
    repo, output = pathlib.Path(repo).resolve(), pathlib.Path(output).resolve()
    names = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=repo
    ).decode("utf-8").split("\0")
    selected = []
    for name in sorted(set(filter(None, names))):
        if not is_product_source(name):
            continue
        source = repo / name
        if source.is_symlink() or not source.resolve().is_relative_to(repo):
            raise ValueError("Source symlink outside product tree")
        if source.is_file():
            selected.append((source, name))
    if not any(name == "engine/go.mod" for _, name in selected):
        raise ValueError("Required engine source is missing")
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        for source, name in selected:
            archive.write(source, name)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix(output.suffix + ".sha256").write_text(digest + "  " + output.name + "\n", encoding="utf-8")
    return len(selected), digest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=pathlib.Path, default=REPO / "service/dist/SmartVoWifiCall-source.zip")
    args = parser.parse_args()
    count, digest = package(REPO, args.output)
    print(f"Packaged {count} product source files; SHA-256 {digest}")
