"""Version-locked data-only log-pool isolation for the bundled SoftVoWiFi engine.

Changes one existing equal-length path literal; no pointer, instruction, ELF
layout, Go metadata, transport, authentication, or protocol code is rewritten.
The original input is never overwritten. Requires installed pyelftools only.
"""
from pathlib import Path
import argparse
import hashlib
import io
import json
import struct
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "deps/vowifi-engine-arm64"
OUTPUT = ROOT / "deps/vowifi-engine-arm64-isolated"
SOURCE_SHA256 = "11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7"
LITERAL_VA = 0x17B35
LITERAL_OFFSET = 0x17B35
GLOBAL_VA = 0x9BE860
GLOBAL_OFFSET = 0x9B2860
OLD_PATH = b"/data/adb/vowifi/state/logs"
NEW_PATH = b"/data/adb/nvwims/state/logs"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def mapping(elf, address, length):
    sections = [s for s in elf.iter_sections()
                if s["sh_type"] != "SHT_NOBITS" and s["sh_addr"] <= address
                and address + length <= s["sh_addr"] + s["sh_size"]]
    segments = [s for s in elf.iter_segments()
                if s["p_type"] == "PT_LOAD" and s["p_vaddr"] <= address
                and address + length <= s["p_vaddr"] + s["p_filesz"]]
    require(len(sections) == 1 and len(segments) == 1, "AMBIGUOUS_ELF_MAPPING")
    section, segment = sections[0], segments[0]
    offset = section["sh_offset"] + address - section["sh_addr"]
    require(offset == segment["p_offset"] + address - segment["p_vaddr"], "ELF_MAPPING_MISMATCH")
    return offset, section.name


def patch(original):
    require(sha256(original) == SOURCE_SHA256, "UNEXPECTED_ENGINE_HASH")
    require(len(OLD_PATH) == len(NEW_PATH) == 27, "PATH_LENGTH_CHANGED")
    elf = ELFFile(io.BytesIO(original))
    require(elf.elfclass == 64 and elf.little_endian and elf["e_machine"] == "EM_AARCH64", "UNEXPECTED_ELF_ABI")
    literal_offset, literal_section = mapping(elf, LITERAL_VA, len(OLD_PATH))
    global_offset, global_section = mapping(elf, GLOBAL_VA, 16)
    require((literal_offset, literal_section) == (LITERAL_OFFSET, ".rodata"), "LITERAL_MAPPING_CHANGED")
    require((global_offset, global_section) == (GLOBAL_OFFSET, ".data"), "GLOBAL_MAPPING_CHANGED")
    require(struct.unpack_from("<QQ", original, global_offset) == (LITERAL_VA, len(OLD_PATH)), "GO_STRING_POINTER_OR_LENGTH_CHANGED")
    require(original[literal_offset:literal_offset + len(OLD_PATH)] == OLD_PATH, "SOURCE_LITERAL_CHANGED")
    require(original.count(OLD_PATH) == 1 and NEW_PATH not in original, "NONUNIQUE_PATH_LITERAL")
    changed = bytearray(original)
    changed[literal_offset:literal_offset + len(OLD_PATH)] = NEW_PATH
    changed = bytes(changed)
    actual_offsets = [i for i, (old, new) in enumerate(zip(original, changed)) if old != new]
    expected_offsets = [literal_offset + i for i, (old, new) in enumerate(zip(OLD_PATH, NEW_PATH)) if old != new]
    require(len(changed) == len(original) and actual_offsets == expected_offsets, "UNEXPECTED_BINARY_DIFFERENCE")
    require(changed[:literal_offset] == original[:literal_offset]
            and changed[literal_offset + len(OLD_PATH):] == original[literal_offset + len(OLD_PATH):], "NONLITERAL_BYTES_CHANGED")
    manifest = {
        "patch": "independent-log-pool-v1",
        "source_sha256": SOURCE_SHA256,
        "output_sha256": sha256(changed),
        "bytes": len(changed),
        "literal": {"virtual_address": hex(LITERAL_VA), "file_offset": hex(literal_offset),
                    "section": literal_section, "length": len(OLD_PATH),
                    "before": OLD_PATH.decode(), "after": NEW_PATH.decode()},
        "go_string_global": {"virtual_address": hex(GLOBAL_VA), "file_offset": hex(global_offset),
                             "pointer": hex(LITERAL_VA), "length": len(OLD_PATH), "unchanged": True},
        "changed_file_offsets": [hex(i) for i in actual_offsets],
        "preserved": ["ELF headers and mappings", "all code sections", "all Go metadata", "all relocations", "all other data"],
        "known_consumers": [
            {"function": "main.main", "global_load_va": "0x838964", "log_new_call_va": "0x8389ac"},
            {"function": "main.runDiagnosticStream.func1", "literal_address_va": "0x844a7c",
             "note": "Log-export pool argument also changes. Its separate /data/adb/vowifi/logs root remains unchanged; do not use this CLI as an independent-directory exporter."}
        ],
        "reference_scan": {
            "full_path_literal_occurrences": 1,
            "nonlogging_direct_references_observed": 0,
            "excluded_numeric_match": "0x8c8a48 is rtype.Str nameOff 0x17b3d, resolving against types base 0x88f4c0 to *cipher.cbcDecAble at 0x8a6ffd; it is not a pointer to the path."
        },
        "limits": "Static data-only patch verification. No target process, phone, network, authentication, call, or SMS was executed."
    }
    return changed, manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=SOURCE)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    require(args.source.resolve() != args.output.resolve(), "NEVER_OVERWRITE_SOURCE")
    original = args.source.read_bytes()
    changed, manifest = patch(original)
    if args.output.exists():
        require(args.output.read_bytes() == changed, "REFUSE_DIFFERENT_EXISTING_OUTPUT")
    else:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(changed)
    require(sha256(args.source.read_bytes()) == SOURCE_SHA256, "SOURCE_CHANGED_DURING_PATCH")
    require(args.output.read_bytes() == changed, "OUTPUT_VERIFICATION_FAILED")
    manifest.update(source=str(args.source.resolve()), output=str(args.output.resolve()))
    manifest_path = args.output.with_name(args.output.name + ".patch.json")
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: manifest[key] for key in ["source_sha256", "output_sha256", "bytes", "changed_file_offsets", "output"]}, indent=2))


if __name__ == "__main__":
    main()
