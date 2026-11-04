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

    def test_unsupported_rust_algorithm_is_error(self):
        p = subprocess.run(
            [
                sys.executable,
                str(Path(run.__file__)),
                "benchmark",
                "--candidate",
                "rust-jni",
                "--algorithm",
                "eytzinger",
            ],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(0, p.returncode)
        self.assertIn("Rust 不支持", p.stderr)

    def test_incomplete_jmh_is_not_reported(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            d = root / "failed"
            d.mkdir()
            (d / "jmh.json").write_text("[{}]")
            (d / "status.json").write_text('{"status":"failed"}')
            self.assertEqual([], run.report(root))

    def test_histogram_sample_count(self):
        metric = {"rawDataHistogram": [[[[0.1, 3], [0.2, 4]]], [[[0.3, 5]]]]}
        self.assertEqual(12, run.sample_count(metric))
        self.assertIsNone(run.sample_count({}))

    def test_expected_quick_coverage(self):
        self.assertEqual(128, len(run.expected_coverage("real")))

    def test_report_distinguishes_hit_rates(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            d = root / "synthetic"
            d.mkdir()
            (d / "status.json").write_text('{"status":"passed"}')
            records = [
                {
                    "benchmark": "bench.rows",
                    "mode": "thrpt",
                    "threads": 1,
                    "params": {
                        "batch": "4096",
                        "prefix": "false",
                        "dataset": "synthetic:10000000",
                        "hitRate": str(hit),
                    },
                    "primaryMetric": {
                        "score": 10,
                        "scoreError": 1,
                        "scoreUnit": "ops/s",
                        "scorePercentiles": {"50.0": 10},
                    },
                }
                for hit in [0, 90]
            ]
            (d / "jmh.json").write_text(json.dumps(records))
            results = run.report(root)
            self.assertEqual(["0", "90"], [r["hitRate"] for r in results])
            self.assertTrue(all(r["p50"] is None for r in results))
            self.assertTrue(all(r["algorithm"] == "binary" for r in results))
            self.assertIn("| 4096 | 1 | 90 |", (root / "report.md").read_text())

    def test_algorithm_coverage_and_legacy_reports(self):
        self.assertEqual(486, len(run.expected_algorithm_coverage(run.ALGORITHMS)))
        self.assertEqual(54, len(run.expected_algorithm_coverage(["eytzinger"])))
        self.assertEqual(9, len(set(run.ALGORITHMS)))
        row = {
            "algorithm": "prefix20",
            "path": "rows",
            "batch": 4096,
            "threads": 4,
            "mode": "thrpt",
            "dataset": "input.parquet",
            "hitRate": "100",
        }
        self.assertIn(
            run.algorithm_coverage_key(row),
            run.expected_algorithm_coverage(run.ALGORITHMS),
        )
        row["dataset"] = "synthetic:10000000"
        self.assertNotIn(
            run.algorithm_coverage_key(row),
            run.expected_algorithm_coverage(run.ALGORITHMS),
        )

    def test_candidate_coverage_and_order(self):
        import candidates

        c, a = candidates.selection("all", "legacy")
        self.assertEqual(324, len(candidates.coverage(c, a)))
        self.assertEqual(162, len(candidates.coverage(["rust-jni"], a)))
        pairs = candidates.order(c, a)
        self.assertEqual(pairs, candidates.order(c, a))
        self.assertEqual(6, len(set(pairs)))
        for i in range(0, 6, 2):
            self.assertEqual(pairs[i][1], pairs[i + 1][1])
            self.assertNotEqual(pairs[i][0], pairs[i + 1][0])
        with self.assertRaises(ValueError):
            candidates.selection("all", "eytzinger")
        self.assertNotEqual(
            candidates.key(
                dict(
                    candidate="java",
                    algorithm="binary",
                    path="rows",
                    batch=1024,
                    threads=1,
                    mode="thrpt",
                    dataset="real",
                    hitRate=100,
                )
            ),
            candidates.key(
                dict(
                    candidate="rust-jni",
                    algorithm="binary",
                    path="rows",
                    batch=1024,
                    threads=1,
                    mode="thrpt",
                    dataset="real",
                    hitRate=100,
                )
            ),
        )

    def test_resume_checks_native_and_query_identity(self):
        import candidates

        original = {
            "git": "abc",
            "nativeSha256": "123",
            "querySha256": "456",
            "candidates": ["java", "rust-jni"],
            "createdAt": "old",
        }
        current = dict(original, createdAt="new")
        candidates.validate_resume(original, current)
        for field in ("git", "nativeSha256", "querySha256", "candidates"):
            changed = dict(current)
            changed[field] = "changed"
            with self.assertRaisesRegex(ValueError, field):
                candidates.validate_resume(original, changed)

    def test_cross_report_missing_duplicate_and_failed(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "manifest.json").write_text(
                json.dumps(
                    dict(
                        crossCandidate=True,
                        action="matrix",
                        candidates=["java", "rust-jni"],
                        algorithms=["binary"],
                        plannedScenarios=["ok", "failed", "absent"],
                    )
                )
            )
            metric = dict(
                benchmark="bench.rows",
                mode="thrpt",
                threads=1,
                params=dict(
                    candidate="java",
                    algorithm="binary",
                    batch="1024",
                    prefix="false",
                    dataset="real",
                    hitRate="100",
                ),
                primaryMetric=dict(score=10, scoreError=0, scoreUnit="ops/s"),
            )
            for name, status in [("ok", "passed"), ("failed", "failed")]:
                d = root / name
                d.mkdir()
                (d / "status.json").write_text(json.dumps(dict(status=status)))
                (d / "jmh.json").write_text(json.dumps([metric, metric]))
            rows = run.report(root)
            self.assertEqual(2, len(rows))
            report = json.loads((root / "summary.json").read_text())
            self.assertEqual(108, report["coverage"]["expected"])
            self.assertEqual(1, report["coverage"]["duplicates"])
            self.assertEqual(107, len(report["coverage"]["missing"]))
            self.assertIn("not_run", [r["status"] for r in report["runs"]])

    def test_matrix(self):
        cases = run.scenarios("real", False)
        self.assertEqual(28, len(cases))
        self.assertEqual(9, sum(c[5] for c in cases))
        self.assertTrue(all(c[3] == 1 for c in cases if c[2] == 1000000))


if __name__ == "__main__":
    unittest.main()
