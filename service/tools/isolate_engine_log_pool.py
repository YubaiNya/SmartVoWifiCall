"""Version-locked data-directory isolation for the bundled SoftVoWiFi engine.

Changes four existing equal-length path literals; no pointer, instruction, ELF
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
PRIOR_LOG_ONLY_SHA256 = "15f8be3814736aa33ca4605c4facb787e13e75cd383ab609d3d4f51b33a3d47d"
# VA and file offset are equal for these four .rodata literals in this exact ELF.
PATHS = (
    (0x1302D, b"/data/adb/vowifi/logs", b"/data/adb/nvwims/logs"),
    (0x17B35, b"/data/adb/vowifi/state/logs", b"/data/adb/nvwims/state/logs"),
    (0x1A65D, b"/data/adb/vowifi/run/call.lock", b"/data/adb/nvwims/run/call.lock"),
    (0x1E2B3, b"/data/adb/vowifi/run/networks.json", b"/data/adb/nvwims/run/networks.json"),
)
STRING_REFERENCES = (
    # (VA, file offset, section, literal VA, byte length)
    (0x9BE860, 0x9B2860, ".data", 0x17B35, 27),
    (0x9AFCD8, 0x9A7CD8, ".data.rel.ro", 0x1A65D, 30),
)


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
    elf = ELFFile(io.BytesIO(original))
    require(elf.elfclass == 64 and elf.little_endian and elf["e_machine"] == "EM_AARCH64", "UNEXPECTED_ELF_ABI")
    changed = bytearray(original)
    expected_offsets, literals, references = [], [], []
    for address, before, after in PATHS:
        require(len(before) == len(after), "PATH_LENGTH_CHANGED")
        offset, section = mapping(elf, address, len(before))
        require((offset, section) == (address, ".rodata"), "LITERAL_MAPPING_CHANGED")
        require(original[offset:offset + len(before)] == before, "SOURCE_LITERAL_CHANGED")
        require(original.count(before) == 1 and after not in original, "NONUNIQUE_PATH_LITERAL")
        changed[offset:offset + len(before)] = after
        expected_offsets.extend(offset + i for i, (a, b) in enumerate(zip(before, after)) if a != b)
        literals.append({"virtual_address": hex(address), "file_offset": hex(offset),
                         "section": section, "length": len(before),
                         "before": before.decode(), "after": after.decode()})
    for address, offset, section, pointer, length in STRING_REFERENCES:
        require(mapping(elf, address, 16) == (offset, section), "GLOBAL_MAPPING_CHANGED")
        require(struct.unpack_from("<QQ", original, offset) == (pointer, length),
                "GO_STRING_POINTER_OR_LENGTH_CHANGED")
        references.append({"virtual_address": hex(address), "file_offset": hex(offset),
                           "section": section, "pointer": hex(pointer), "length": length,
                           "unchanged": True})
    changed = bytes(changed)
    actual_offsets = [i for i, (old, new) in enumerate(zip(original, changed)) if old != new]
    require(len(changed) == len(original) and actual_offsets == expected_offsets, "UNEXPECTED_BINARY_DIFFERENCE")
    require(len(actual_offsets) == 16 and b"/data/adb/vowifi/" not in changed, "LEGACY_DATA_PATH_REMAINS")
    manifest = {
        "patch": "independent-data-directories-v2",
        "source_sha256": SOURCE_SHA256,
        "output_sha256": sha256(changed),
        "bytes": len(changed),
        "literals": literals,
        "go_string_references": references,
        "changed_file_offsets": [hex(i) for i in actual_offsets],
        "preserved": ["ELF headers and mappings", "all code sections", "all Go metadata", "all relocations", "all other data"],
        "known_consumers": [
            {"function": "main.main", "default_log_root_va": "0x838770",
             "pool_global_load_va": "0x838964", "log_new_call_va": "0x8389ac"},
            {"function": "main.runDiagnosticStream.func1", "log_root_reference_va": "0x844a70",
             "pool_reference_va": "0x844a7c",
             "note": "Both defaults now use nvwims; an explicit --log-dir elsewhere still differs from this fixed diagnostic export root."},
            {"function": "main.productionIMSConfigWithInterface", "config_template_reference_va": "0x830694",
             "template_va": "0x9afc60", "CallAdmissionPath_offset": "0x78"},
            {"function": "vowifi_engine/internal/underlay.RuntimeRegistry", "network_path_reference_va": "0x746140",
             "note": "Fresh network snapshot remains required; only its path is relocated."}
        ],
        "reference_scan": {
            "occurrences_of_each_full_path_literal": 1,
            "remaining_legacy_data_root_occurrences": 0,
            "old_module_path_or_package_literals_observed": 0
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
        existing = args.output.read_bytes()
        require(existing == changed or sha256(existing) == PRIOR_LOG_ONLY_SHA256,
                "REFUSE_DIFFERENT_EXISTING_OUTPUT")
        if existing != changed:
            args.output.write_bytes(changed)
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
