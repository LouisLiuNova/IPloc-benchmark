"""资源保护和报告状态测试，不运行重型负载。"""

import json, os, subprocess, sys, tempfile, unittest
from pathlib import Path
from unittest.mock import patch
import run


class RunnerTests(unittest.TestCase):
    def test_success_and_failure(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            self.assertEqual(
                "passed",
                run.guarded([sys.executable, "-c", "print(123)"], root / "ok")[
                    "status"
                ],
            )
            self.assertEqual(
                "failed",
                run.guarded(
                    [sys.executable, "-c", "raise SystemExit(7)"], root / "bad"
                )["status"],
            )

    def test_low_memory_prevents_launch(self):
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(run, "available", return_value=run.GIB),
            patch.object(run.subprocess, "Popen") as launch,
        ):
            self.assertEqual(
                "resource_stopped", run.guarded(["unused"], Path(temp))["status"]
            )
            launch.assert_not_called()

    def test_two_samples_stop_entire_group(self):
        with (
            tempfile.TemporaryDirectory() as temp,
            patch.object(run, "available", return_value=8 * run.GIB),
            patch.object(run, "group_usage", return_value=(13 * run.GIB, 0)),
        ):
            result = run.guarded(
                [sys.executable, "-c", "import time;time.sleep(60)"], Path(temp)
            )
            self.assertEqual("resource_stopped", result["status"])
            self.assertLess(result["seconds"], 10)

    def test_explicit_rust_is_error(self):
        p = subprocess.run(
            [
                sys.executable,
                str(Path(run.__file__)),
                "benchmark",
                "--candidate",
                "rust-jni",
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, p.returncode)
        self.assertIn("not_implemented", p.stderr)

    def test_incomplete_jmh_is_not_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            d = root / "failed"
            d.mkdir()
            (d / "jmh.json").write_text("[{}]")
            (d / "status.json").write_text('{"status":"failed"}')
            self.assertEqual([], run.report(root))

    def test_matrix(self):
        cases = run.scenarios("real", False)
        self.assertEqual(28, len(cases))
        self.assertEqual(9, sum(c[5] for c in cases))
        self.assertTrue(all(c[3] == 1 for c in cases if c[2] == 1000000))


if __name__ == "__main__":
    unittest.main()
