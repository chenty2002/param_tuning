import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import runner
from search import PROPERTY_NAMES
from search import BASELINE


class RunnerTest(unittest.TestCase):
    def test_vendored_tree_state_tracks_staged_and_untracked_sources(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            project = root / "example"
            project.mkdir()
            (project / ".gitignore").write_text("out/\n")
            source = project / "source.scala"
            source.write_text("original")
            original = runner.git_state(project)
            self.assertEqual(original["revision"], "uncommitted")
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            self.assertEqual(runner.git_state(project), original)
            source.write_text("changed")
            changed = runner.git_state(project)
            self.assertNotEqual(changed["tree_sha256"], original["tree_sha256"])
            (project / "out").mkdir()
            (project / "out" / "generated.sv").write_text("generated")
            self.assertEqual(runner.git_state(project), changed)
            (project / "new.scala").write_text("untracked source")
            self.assertNotEqual(runner.git_state(project)["tree_sha256"], changed["tree_sha256"])

    def test_continuous_search_rechecks_and_stops_at_blocker(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp) / "selected"
            row = {"rank": 1, "hash": "selected", "path": str(directory),
                   "candidate": BASELINE}
            pending = {"verdict": "pending_recheck", "best": row, "next": None}
            with patch("sys.argv", ["runner.py", "--search", "--runs", str(Path(tmp) / "runs")]), \
                    patch("builtins.print"), \
                    patch.object(runner, "VERILOG", Path(tmp)), \
                    patch.object(runner, "sha", return_value="hash"), \
                    patch.object(runner, "inspect", return_value=pending), \
                    patch.object(runner, "git_revision", return_value="rev"), \
                    patch.object(runner, "git_state", return_value={"revision": "rev"}), \
                    patch.object(runner, "execute", return_value=(-1, 0.1)) as execute:
                self.assertTrue(runner.main())
                self.assertTrue(Path(str(directory) + "-recheck", "result.json").is_file())
                self.assertEqual(execute.call_count, 1)
                blocker = {"verdict": "undetermined", "best": row, "next": None}
                with patch.object(runner, "inspect", return_value=blocker):
                    self.assertFalse(runner.main())
                self.assertEqual(execute.call_count, 1)

    def test_resume_rejects_changed_source_or_sv(self):
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / "VerifyTop.sv"
            output.write_text("original generated SV")
            current = dict.fromkeys(("candidate", "source_sha256", "source_state",
                                     "submodule_revisions", "tcl_sha256", "helper_sha256"), "same")
            saved = current | {"sv_sha256": runner.sha(output)}
            runner.validate_resume(saved, current, output)
            with self.assertRaisesRegex(ValueError, "provenance differs"):
                runner.validate_resume(saved, current | {"source_sha256": "changed"}, output)
            output.write_text("changed SV")
            with self.assertRaisesRegex(ValueError, "SV differs"):
                runner.validate_resume(saved, current, output)

    def test_missing_family_blocks_feasibility_even_with_success_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            log = directory / "jg.log"
            log.write_text("JG completed with exit 0\n")
            (directory / "property_manifest.tsv").write_text(
                "<embedded>::VerifyTop.param_way_index__l1_0\tassert\tproven\n")
            self.assertEqual(runner.parse_jg(directory, log)[0], "property_missing")

    def test_jg_proven_status_matches_search_result_vocabulary(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            log = directory / "jg.log"
            log.write_text("JG completed\n")
            names = [f"VerifyTop.{family}__l1_0" for family in sorted(PROPERTY_NAMES)]
            cover = "VerifyTop.param_cover_two_mshrs__l1_0"
            (directory / "property_manifest.tsv").write_text("".join(
                f"{name}\tassert\tproven\n" for name in names) +
                f"{cover}\tcover\tcovered\n")
            (directory / "property_status.tsv").write_text(
                "prove_result\tdetermined\n" + "".join(
                    f"{name}\tassert\tproven\tprocessed\n" for name in names) +
                f"{cover}\tcover\tcovered\tprocessed\n")
            status, _, properties, coverage = runner.parse_jg(directory, log)
            self.assertEqual(status, "feasible_proved")
            self.assertEqual(set(properties.values()), {"proved"})
            self.assertTrue(coverage[cover]["reached"])
            status_file = directory / "property_status.tsv"
            valid_status = status_file.read_text()
            for corrupted in (
                valid_status.replace("prove_result\tdetermined", "prove_result"),
                valid_status + f"{names[0]}\tassert\tproven\tprocessed\n",
                valid_status + "truncated\tassert\n",
                valid_status.replace("\tprocessed", "\tunexpected"),
            ):
                with self.subTest(status=corrupted[:40]):
                    status_file.write_text(corrupted)
                    self.assertEqual(runner.parse_jg(directory, log)[0], "unknown")
            status_file.write_text(valid_status)
            manifest = directory / "property_manifest.tsv"
            manifest.write_text(manifest.read_text() + f"{names[0]}\tassert\tproven\n")
            self.assertEqual(runner.parse_jg(directory, log)[0], "property_missing")

    def test_candidate_geometry_requires_exact_property_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            log = directory / "jg.log"
            log.write_text("JG completed\n")
            assertions, covers = runner.expected_manifest(BASELINE)
            (directory / "property_manifest.tsv").write_text("".join(
                f"<embedded>::{name}\tassert\tunprocessed\n" for name in sorted(assertions)
                if not name.endswith("param_ram_data__readback")) + "".join(
                f"<embedded>::{name}\tcover\tunprocessed\n" for name in sorted(covers)))
            self.assertEqual(runner.parse_jg(directory, log, BASELINE)[0], "property_missing")

    def test_miss_coverage_is_required_for_both_l2_instances(self):
        candidate = BASELINE | {"l2.sets": 2}
        assertions, covers = runner.expected_manifest(candidate)
        required = {"VerifyTop.param_cover_miss__l2_0", "VerifyTop.param_cover_miss__l2_1"}
        self.assertTrue(required <= covers)
        self.assertEqual((len(assertions), len(covers)), (118, 25))
        with tempfile.TemporaryDirectory() as tmp:
            directory = Path(tmp)
            log = directory / "jg.log"
            log.write_text("JG completed\n")
            for missing in required:
                with self.subTest(missing=missing):
                    (directory / "property_manifest.tsv").write_text("".join(
                        f"{name}\tassert\tproven\n" for name in sorted(assertions)) + "".join(
                        f"{name}\tcover\tcovered\n" for name in sorted(covers - {missing})))
                    self.assertEqual(runner.parse_jg(directory, log, candidate)[0], "property_missing")


if __name__ == "__main__":
    unittest.main()
