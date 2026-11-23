"""Verify the packaged isolation candidate without executing the Android engine."""
from pathlib import Path
import hashlib
import io
import json
import struct
import subprocess
import sys
import unittest
from elftools.elf.elffile import ELFFile

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "tools"))
import isolate_engine_log_pool as patcher

OUTPUT_SHA256 = "f53df163c7731ebeba45ebf913e85cace0af2884647359155ed515e137b479b3"


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
        self.assertEqual(differences, [0x13037, 0x13038, 0x1303B, 0x1303C,
            0x17B3F, 0x17B40, 0x17B43, 0x17B44,
            0x1A667, 0x1A668, 0x1A66B, 0x1A66C,
            0x1E2BD, 0x1E2BE, 0x1E2C1, 0x1E2C2])
        self.assertNotIn(b"/data/adb/vowifi/", self.output)
        for offset, before, after in patcher.PATHS:
            self.assertEqual(self.output[offset:offset + len(after)], after)
            self.assertNotIn(before, self.output)
            self.assertEqual(self.output.count(after), 1)

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
        self.assertEqual(patcher.mapping(new, 0x9AFCD8, 16), (0x9A7CD8, ".data.rel.ro"))
        self.assertEqual(struct.unpack_from("<QQ", self.output, 0x9A7CD8), (0x1A65D, 30))
        for offset, before, after in patcher.PATHS:
            self.assertEqual(patcher.mapping(new, offset, len(after)), (offset, ".rodata"))

    def test_reproduction_and_version_guard(self):
        result, manifest = patcher.patch(self.source)
        self.assertEqual(result, self.output)
        self.assertEqual(manifest["output_sha256"], OUTPUT_SHA256)
        corrupt = bytearray(self.source); corrupt[0] ^= 1
        with self.assertRaisesRegex(ValueError, "UNEXPECTED_ENGINE_HASH"):
            patcher.patch(bytes(corrupt))
        with self.assertRaisesRegex(ValueError, "UNEXPECTED_ENGINE_HASH"):
            patcher.patch(self.output)

    def test_dependency_metadata_and_manifest_match_candidate(self):
        root = patcher.OUTPUT.parent
        manifest = json.loads((root / (patcher.OUTPUT.name + ".patch.json")).read_text())
        dependency = json.loads((root / "engine.source.json").read_text())
        self.assertEqual(manifest["patch"], "independent-data-directories-v2")
        self.assertEqual(manifest["output_sha256"], OUTPUT_SHA256)
        self.assertEqual(dependency["sha256"], OUTPUT_SHA256)
        self.assertEqual(dependency["upstreamSha256"], patcher.SOURCE_SHA256)
        self.assertEqual(len(manifest["literals"]), 4)
        self.assertEqual(len(manifest["changed_file_offsets"]), 16)

    def test_baseline_cannot_be_output(self):
        completed = subprocess.run([sys.executable, str(Path(patcher.__file__)), "--output", str(patcher.SOURCE)], capture_output=True, text=True)
        self.assertNotEqual(completed.returncode, 0)
        self.assertIn("NEVER_OVERWRITE_SOURCE", completed.stderr)
        self.assertEqual(patcher.SOURCE.read_bytes(), self.source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
