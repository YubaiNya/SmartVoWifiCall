"""Verify the packaged isolation candidate without executing the Android engine."""
from pathlib import Path
import hashlib
import io
import struct
import subprocess
import sys
import unittest
from elftools.elf.elffile import ELFFile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import isolate_engine_log_pool as patcher

OUTPUT_SHA256 = "15f8be3814736aa33ca4605c4facb787e13e75cd383ab609d3d4f51b33a3d47d"


class IsolatedPoolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = patcher.SOURCE.read_bytes()
        cls.output = patcher.OUTPUT.read_bytes()

    def test_candidate_identity_and_exact_delta(self):
        self.assertEqual(hashlib.sha256(self.source).hexdigest(), patcher.SOURCE_SHA256)
        self.assertEqual(hashlib.sha256(self.output).hexdigest(), OUTPUT_SHA256)
        self.assertEqual(len(self.source), len(self.output))
        differences = [i for i, (a, b) in enumerate(zip(self.source, self.output)) if a != b]
        self.assertEqual(differences, [0x17B3F, 0x17B40, 0x17B43, 0x17B44])
        self.assertEqual(self.output[0x17B35:0x17B35 + 27], b"/data/adb/nvwims/state/logs")
        self.assertNotIn(b"/data/adb/vowifi/state/logs", self.output)
        self.assertEqual(self.output.count(b"/data/adb/nvwims/state/logs"), 1)

    def test_elf_mappings_and_every_other_section_unchanged(self):
        old = ELFFile(io.BytesIO(self.source)); new = ELFFile(io.BytesIO(self.output))
        self.assertEqual(dict(old.header), dict(new.header))
        self.assertEqual([dict(s.header) for s in old.iter_segments()], [dict(s.header) for s in new.iter_segments()])
        for old_section, new_section in zip(old.iter_sections(), new.iter_sections()):
            self.assertEqual(dict(old_section.header), dict(new_section.header))
            if old_section.name != ".rodata":
                self.assertEqual(old_section.data(), new_section.data(), old_section.name)
        self.assertEqual(patcher.mapping(new, 0x17B35, 27), (0x17B35, ".rodata"))
        self.assertEqual(patcher.mapping(new, 0x9BE860, 16), (0x9B2860, ".data"))
        self.assertEqual(struct.unpack_from("<QQ", self.output, 0x9B2860), (0x17B35, 27))

    def test_reproduction_and_version_guard(self):
        result, manifest = patcher.patch(self.source)
        self.assertEqual(result, self.output)
        self.assertEqual(manifest["output_sha256"], OUTPUT_SHA256)
        corrupt = bytearray(self.source); corrupt[0] ^= 1
        with self.assertRaisesRegex(ValueError, "UNEXPECTED_ENGINE_HASH"):
            patcher.patch(bytes(corrupt))
        with self.assertRaisesRegex(ValueError, "UNEXPECTED_ENGINE_HASH"):
            patcher.patch(self.output)

    def test_baseline_cannot_be_output(self):
        completed = subprocess.run([sys.executable, str(Path(patcher.__file__)), "--output", str(patcher.SOURCE)], capture_output=True, text=True)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("NEVER_OVERWRITE_SOURCE", completed.stderr)
        self.assertEqual(patcher.SOURCE.read_bytes(), self.source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
