"""Executable checks for the draft wire shapes and declared transition matrix."""
import copy
import json
import unittest
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parent
SCHEMA = json.loads((ROOT / "execution.schema.json").read_text(encoding="utf-8-sig"))
EXAMPLES = json.loads((ROOT / "examples.json").read_text(encoding="utf-8-sig"))
TRANSITIONS = json.loads((ROOT / "task-transitions.json").read_text(encoding="utf-8-sig"))


def validator(name):
    schema = {"$schema": SCHEMA["$schema"], "$defs": SCHEMA["$defs"],
              "$ref": "#/$defs/" + name}
    return Draft202012Validator(schema, format_checker=FormatChecker())


class ContractTests(unittest.TestCase):
    def test_schema_and_examples(self):
        Draft202012Validator.check_schema(SCHEMA)
        for name, example in EXAMPLES.items():
            with self.subTest(name=name):
                validator(name).validate(example)
                Draft202012Validator(SCHEMA, format_checker=FormatChecker()).validate(example)

    def test_required_fields_and_unknown_fields(self):
        for name, example in EXAMPLES.items():
            for field in SCHEMA["$defs"][name]["required"]:
                with self.subTest(name=name, missing=field):
                    value = copy.deepcopy(example)
                    del value[field]
                    self.assertFalse(validator(name).is_valid(value))
            with self.subTest(name=name, unknown=True):
                self.assertFalse(validator(name).is_valid({**example, "admin": True}))

    def test_invalid_values(self):
        cases = [
            ("TaskPackage", ["schemaVersion"], "2"),
            ("TaskPackage", ["revision"], 0),
            ("TaskPackage", ["revision"], True),
            ("TaskPackage", ["baseCommit"], "abc123"),
            ("TaskPackage", ["baseCommit"], "A" * 40),
            ("TaskPackage", ["dependencies", 0, "gate"], "SUCCEEDED"),
            ("TaskPackage", ["dependencies", 0, "revision"], 0),
            ("TaskPackage", ["ownedPaths"], ["../secret"]),
            ("TaskPackage", ["ownedPaths"], ["src/../../secret"]),
            ("TaskPackage", ["ownedPaths"], ["/etc/passwd"]),
            ("TaskPackage", ["ownedPaths"], ["C:\\secret"]),
            ("TaskPackage", ["ownedPaths"], ["src\\secret"]),
            ("TaskPackage", ["ownedPaths"], []),
            ("TaskPackage", ["resources", "cpu"], 0),
            ("TaskPackage", ["resources", "memoryMiB"], -1),
            ("TaskPackage", ["maxAttempts"], 0),
            ("TaskPackage", ["budget", "tokenBudget"], -1),
            ("TaskPackage", ["budget", "costBudget", "limit"], 30),
            ("TaskPackage", ["budget", "costBudget", "limit"], "1.234"),
            ("TaskPackage", ["requiredCapabilities"], ["java", "java"]),
            ("TaskSnapshot", ["state"], "DONE"),
            ("TaskSnapshot", ["rowVersion"], 0),
            ("TaskAttempt", ["attemptNumber"], 0),
            ("TaskAttempt", ["leaseEpoch"], 0),
            ("TaskLease", ["expiresAt"], "2026-02-30T00:00:00Z"),
            ("TaskLease", ["expiresAt"], "2026-11-09T00:00:00"),
            ("TaskLease", ["leaseEpoch"], 9007199254740992),
            ("ResultSubmission", ["resultCommit"], None),
            ("ResultSubmission", ["resultDigest"], "sha256:abc"),
            ("ResultSubmission", ["manifestUri"], "relative/path"),
            ("ResultSubmission", ["evidence"], []),
        ]
        for name, path, invalid_value in cases:
            with self.subTest(name=name, path=path, value=invalid_value):
                value = copy.deepcopy(EXAMPLES[name])
                target = value
                for part in path[:-1]:
                    target = target[part]
                target[path[-1]] = invalid_value
                self.assertFalse(validator(name).is_valid(value))

    def test_supported_variants(self):
        task = copy.deepcopy(EXAMPLES["TaskPackage"])
        task["parentTaskId"] = None
        task["baseCommit"] = "f" * 64
        task["dependencies"] = []
        task["budget"]["costBudget"]["limit"] = "0.00"
        validator("TaskPackage").validate(task)
        result = copy.deepcopy(EXAMPLES["ResultSubmission"])
        result["outcome"] = "FAILED"
        result["resultCommit"] = None
        validator("ResultSubmission").validate(result)

    def test_transition_matrix_integrity(self):
        states = set(SCHEMA["$defs"]["TaskState"]["enum"])
        self.assertEqual(set(TRANSITIONS), states)
        terminals = {"SUCCEEDED", "FAILED", "CANCELLED", "SUPERSEDED"}
        for state, targets in TRANSITIONS.items():
            self.assertEqual(len(targets), len(set(targets)))
            self.assertTrue(set(targets) <= states)
            if state in terminals:
                self.assertEqual(targets, [])
            else:
                self.assertTrue(targets)
        self.assertEqual(TRANSITIONS["CANCEL_REQUESTED"], ["CANCELLED"])
        self.assertNotIn("SUCCEEDED", TRANSITIONS["RUNNING"])
        self.assertNotIn("SUPERSEDED", TRANSITIONS["RUNNING"])
        self.assertNotIn("CANCELLED", TRANSITIONS["RUNNING"])
        reachable = {"DRAFT"}
        while True:
            expanded = reachable | {target for state in reachable for target in TRANSITIONS[state]}
            if expanded == reachable:
                break
            reachable = expanded
        self.assertEqual(reachable, states)


if __name__ == "__main__":
    unittest.main(verbosity=2)
