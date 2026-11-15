import json
import itertools
import hashlib
import tempfile
import unittest
from pathlib import Path

import search


class SearchTest(unittest.TestCase):
    def test_heap_order_matches_complete_sort(self):
        ranges = {"l1.ways": range(2, 5), "l2.sets": range(2, 7, 2),
                  "ram.width": range(5, 8)}
        expected = sorted((search.BASELINE | dict(zip(ranges, values))
                           for values in itertools.product(*ranges.values())), key=search.score)
        self.assertEqual(list(search.candidates(ranges)), expected)

    def test_pruning_matches_exhaustive_sort_and_counts(self):
        ranges = {"l1.ways": range(1, 4), "l2.sets": range(2, 11, 2),
                  "l3.mshrs": range(2, 9, 2), "l3.sets": range(2, 17, 2),
                  "ram.width": range(1, 7)}
        raw = [search.BASELINE | dict(zip(ranges, values))
               for values in itertools.product(*ranges.values())]
        expected = sorted((p for p in raw if search.static_reject(p) is None),
                          key=search.score)
        spaces = list(search.static_spaces(ranges))
        self.assertEqual(list(search.eligible_candidates(spaces)), expected)
        with tempfile.TemporaryDirectory() as tmp:
            report = search.inspect(ranges, 0, Path(tmp))
        self.assertEqual(report["total"], len(raw))
        self.assertEqual(report["eligible_total"], len(expected))
        self.assertEqual(report["counts"]["static_reject"], len(raw) - len(expected))
        self.assertEqual(sum(report["counts"].values()), len(raw))

    def test_expanded_lower_bounds_and_unreachable_known_configuration(self):
        ranges, _ = search.parse_config(Path(search.__file__).with_name("search.json"))
        spaces = list(search.static_spaces(ranges))
        first = next(search.eligible_candidates(spaces))
        self.assertEqual(first, search.BASELINE | {"l2.sets": 2})
        known = search.BASELINE | {"l2.sets": 2}
        self.assertTrue(all(known[key] in values for key, values in ranges.items()))
        # min + n*step is unchanged: step=4 from min=2 excludes every power of two >2.
        excluded = dict(ranges, **{"l3.sets": range(2, 129, 4)})
        self.assertEqual({value for space in search.static_spaces(excluded)
                          for value in space["l3.sets"]}, set())

    def test_l3_set_space_is_strictly_larger_even_with_more_ways(self):
        for change in ({"l3.sets": 2}, {"l3.sets": 2, "l3.ways": 4},
                       {"l2.sets": 4, "l3.sets": 4}, {"l2.sets": 8, "l3.sets": 4}):
            candidate = search.BASELINE | {"l2.sets": 2} | change
            with self.subTest(change=change):
                self.assertIn("l3.sets must be > l2.sets", search.static_reject(candidate))
        self.assertIsNone(search.static_reject(search.BASELINE | {"l2.sets": 2}))

    def test_empty_static_space_and_fixed_geometry(self):
        for change in ({"l1.ways": range(-1, 2)}, {"ram.width": range(1, 5)},
                       {"l3.blockBytes": range(4, 5)}, {"l2.channelBytes": range(2, 3)}):
            ranges = {"l2.sets": range(2, 3)} | change
            with self.subTest(ranges=ranges), tempfile.TemporaryDirectory() as tmp:
                report = search.inspect(ranges, 0, Path(tmp))
                self.assertEqual(report["eligible_total"], 0)
                self.assertEqual(report["counts"]["static_reject"], report["total"])
                self.assertEqual(report["verdict"], "not_found")
                self.assertIsNone(report["next"])

    def test_order_and_fail_closed_results(self):
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp)
            ranges = {"l1.ways": range(1, 4), "l2.sets": range(2, 3)}
            first, second = search.eligible_candidates(list(search.static_spaces(ranges)))
            self.assertEqual([search.score(p)[0] for p in (first, second)], [48, 56])
            self.assertEqual(search.inspect(ranges, 0, runs)["next"]["candidate"], first)

            path = runs / search.digest(first)
            path.mkdir()
            record = {"candidate": first, "status": "tool_failed"}
            (path / "result.json").write_text(json.dumps(record))
            report = search.inspect(ranges, 0, runs)
            self.assertEqual(report["blocker"]["rank"], 1)
            self.assertEqual(report["counts"]["static_reject"], 1)
            self.assertEqual(sum(report["counts"].values()), 3)
            self.assertIsNone(report["next"])

            record["status"] = "bounded_no_cex"
            (path / "result.json").write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError, "missing feasibility evidence"):
                search.inspect(ranges, 0, runs)

            record["status"] = "cex"
            record["source_sha256"] = "old-monitor"
            (path / "result.json").write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError, "result evidence is stale"):
                search.inspect(ranges, 0, runs)
            del record["source_sha256"]
            (path / "result.json").write_text(json.dumps(record))
            self.assertEqual(search.inspect(ranges, 0, runs)["next"]["candidate"], second)
            limited = search.inspect(ranges, 1, runs)
            self.assertIsNone(limited["next"])
            self.assertEqual(limited["verdict"], "candidate_limit")
            self.assertEqual(limited["attempted"], 1)

            record["status"] = "static_reject"
            (path / "result.json").write_text(json.dumps(record))
            with self.assertRaisesRegex(ValueError, "static rejection disagrees"):
                search.inspect(ranges, 0, runs)
            record["status"] = "cex"
            (path / "result.json").write_text(json.dumps(record))

            path2 = runs / search.digest(second)
            path2.mkdir()
            good = {"candidate": second, "status": "bounded_no_cex",
                    "sv_sha256": "sv", "tcl_sha256": hashlib.sha256(
                        (search.VERILOG / "verify.tcl").read_bytes()).hexdigest(),
                    "source_sha256": hashlib.sha256((search.PROJECT /
                        "src/test/scala/coupledL2Verification/VerifyTop.scala").read_bytes()).hexdigest(),
                    "helper_sha256": {name: hashlib.sha256((search.VERILOG /
                        name).read_bytes()).hexdigest()
                        for name in ("LogPerfHelper.v", "ResetCounter.sv", "STD_CLKGT_func.v",
                                     "ClockGate.v", "TLLogWriter.v")},
                    "source_revision": "source",
                    "submodule_revisions": {"coupledL2": "rev"},
                    "property_version": "p", "assumption_version": "a",
                    "tool_versions": {"jg": "v"}, "generate_log": "gen.log",
                    "jg_log": "jg.log", "elapsed_seconds": 1,
                    "expected_properties": sorted(search.PROPERTY_NAMES),
                    "properties": {name: "bounded_no_cex" for name in search.PROPERTY_NAMES},
                    "expected_coverage": ["param_way_two_valid"],
                    "coverage": {"param_way_two_valid": {"reached": True}}}
            (path2 / "result.json").write_text(json.dumps(good))
            self.assertEqual(search.inspect(ranges, 0, runs)["verdict"], "pending_recheck")
            recheck = runs / (search.digest(second) + "-recheck")
            recheck.mkdir()
            (recheck / "result.json").write_text(json.dumps(good))
            self.assertEqual(search.inspect(ranges, 0, runs)["verdict"], "minimum")
            self.assertIsNone(search.inspect(ranges, 0, runs)["next"])
            good["tcl_sha256"] = "stale"
            (path2 / "result.json").write_text(json.dumps(good))
            with self.assertRaisesRegex(ValueError, "evidence is stale"):
                search.inspect(ranges, 0, runs)

    def test_config_rejects_bool_duplicate_and_unknown(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "config.json"
            for content in (
                '{"parameters":{},"search":{"max_candidates":true}}',
                '{"parameters":{},"search":{"max_candidates":0,"max_candidates":1}}',
                '{"parameters":{"l4.ways":{"min":2,"max":3,"step":1}},"search":{"max_candidates":0}}',
            ):
                path.write_text(content)
                with self.assertRaises(ValueError):
                    search.parse_config(path)

    def test_prepare_records_bulk_static_rejection(self):
        with tempfile.TemporaryDirectory() as tmp:
            runs = Path(tmp)
            ranges = {"l1.ways": range(1, 3), "l2.sets": range(2, 3)}
            next_row = search.inspect(ranges, 0, runs)["next"]
            search.prepare(ranges, runs, next_row)
            rejected = search.BASELINE | {"l1.ways": 1, "l2.sets": 2}
            self.assertFalse((runs / search.digest(rejected)).exists())
            evidence, = (runs / "static-pruning").glob("*.json")
            pruning = json.loads(evidence.read_text())
            self.assertEqual(pruning["total"] - pruning["eligible_total"], 1)
            search.prepare(ranges, runs, next_row)
            self.assertEqual(len(list((runs / "static-pruning").glob("*.json"))), 1)
            self.assertTrue((Path(next_row["path"]) / "candidate.json").exists())


if __name__ == "__main__":
    unittest.main()
