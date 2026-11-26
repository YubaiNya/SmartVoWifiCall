"""Check privacy boundaries even when old internal reports are Git tracked."""
import importlib.util
import pathlib
import unittest

SCRIPT = pathlib.Path(__file__).resolve().parents[2] / "tools/package_source.py"
spec = importlib.util.spec_from_file_location("source_package", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SourcePackageTest(unittest.TestCase):
    def test_required_source_and_licenses(self):
        for name in ["engine/go.mod", "engine/internal/runtime/engine.go", "engine/internal/runtime/recovery_test.go",
                     "engine/internal/ikev2/testdata/auth-aosp-vectors.json", "service/tools/build.py",
                     "service/module/licenses/go-toolchain/LICENSE", "LICENSE", "service/app/build.gradle.kts"]:
            with self.subTest(name=name):
                self.assertTrue(module.is_product_source(name))

    def test_reports_and_secrets_never_ship(self):
        for name in ["service/docs/CTEXCEL_VALIDATION.md", "VERSION_HISTORY.md", "UpLog.md", "TODO.md",
                     "service/local-signing.p12", "service/local.properties", "service/tests/policy/.build/telemetry-batch.json",
                     "service/module/logs/device.log", "engine/build/runtime.json", "service/app/src/main/assets/private.json",
                     "service/tests/sms/ADAPTER-REVIEW.md", "service/.ai-local/research.txt", "../research/data.go"]:
            with self.subTest(name=name):
                self.assertFalse(module.is_product_source(name))


if __name__ == "__main__":
    unittest.main()
