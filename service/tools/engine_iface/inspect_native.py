"""Read-only, version-bound Go/AArch64 inspection for the bundled 0.3.48 engine.

Uses installed pyelftools/Capstone. Does not run or modify the Android executable.
The metadata textStart is relocated to .text+0x100 in this exact artifact.
"""
from pathlib import Path
import argparse
import hashlib
import io
import struct
from elftools.elf.elffile import ELFFile
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN

SOURCE = Path(__file__).resolve().parents[2] / "deps/vowifi-engine-arm64"
SOURCE_SHA256 = "11864d79088731cb898518d21694e4d4068eb680e0ae11cc7f5b7f0a06a53be7"


class GoImage:
    def __init__(self, source=SOURCE):
        self.data = Path(source).read_bytes()
        self.sha256 = hashlib.sha256(self.data).hexdigest()
        if self.sha256 != SOURCE_SHA256:
            raise ValueError("UNEXPECTED_ENGINE_HASH")
        self.elf = ELFFile(io.BytesIO(self.data))
        self.sections = list(self.elf.iter_sections())
        table = self.elf.get_section_by_name(".gopclntab").data()
        count, _, _, names, _, _, _, funcs = struct.unpack_from("<8Q", table, 8)
        text = self.elf.get_section_by_name(".text")["sh_addr"] + 0x100
        self.functions = {}
        for index in range(count):
            entry, offset = struct.unpack_from("<II", table, funcs + 8 * index)
            end = struct.unpack_from("<I", table, funcs + 8 * (index + 1))[0]
            name_offset = names + struct.unpack_from("<i", table, funcs + offset + 4)[0]
            name = table[name_offset:table.index(0, name_offset)].decode()
            self.functions[name] = (text + entry, text + end)
        self.names = {start: name for name, (start, _) in self.functions.items()}
        assert self.functions["vowifi_engine/internal/config.ValidTransport"][0] == 0x6C1990
        assert self.read(0x85DD, 6) == b"iface:"

    def read(self, address, size):
        for section in self.sections:
            if section["sh_type"] != "SHT_NOBITS" and section["sh_addr"] <= address and address + size <= section["sh_addr"] + section["sh_size"]:
                offset = section["sh_offset"] + address - section["sh_addr"]
                return self.data[offset:offset + size]
        raise ValueError(f"UNMAPPED_ADDRESS:{address:#x}")

    def assembly(self, name):
        start, end = self.functions[name]
        result = [f"{name} {start:#x}..{end:#x}"]
        decoder = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        for ins in decoder.disasm(self.read(start, end - start), start):
            target = int(ins.op_str[1:], 16) if ins.mnemonic in ("b", "bl") and ins.op_str.startswith("#0x") else 0
            result.append(f"{ins.address:#x}: {ins.mnemonic} {ins.op_str} {self.names.get(target, '')}".rstrip())
        return "\n".join(result)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("names", nargs="*")
    parser.add_argument("--list", metavar="SUBSTRING")
    args = parser.parse_args()
    image = GoImage()
    if args.list is not None:
        for name, (start, end) in image.functions.items():
            if args.list in name:
                print(f"{start:#x}..{end:#x} {name}")
    for name in args.names:
        print(image.assembly(name))
