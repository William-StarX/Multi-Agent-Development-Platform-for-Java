"""Contracts for the trusted project baseline and generated task scopes."""
from copy import deepcopy
import json
from pathlib import Path
import runpy
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[1]
GENERATOR = runpy.run_path(str(ROOT / "scripts/Generate-Project.py"))


def example(filename):
    return json.loads((ROOT / "examples/projects" / filename).read_text(encoding="utf-8"))


class ProjectGenerationTest(unittest.TestCase):
    def test_optional_adapters_can_be_removed_without_changing_work_scopes(self):
        manifest = example("reservations.json")
        full, tasks = GENERATOR["normalize"](manifest)
        minimal, same_tasks = GENERATOR["normalize"](manifest, minimal=True)
        self.assertEqual(tasks, same_tasks)
        self.assertEqual({task["manager"] for task in tasks}, {"engineering"})
        self.assertEqual({task["scope"] for task in tasks}, {"allocation-service", "catalog-service"})
        self.assertEqual(len({task["capability"] for task in tasks}), len(tasks))

        with TemporaryDirectory() as folder:
            root = Path(folder)
            for name, project in (("full", full), ("minimal", minimal)):
                GENERATOR["render_tree"](project, root / name)
            full_stack = json.loads((root / "full/compose.json").read_text(encoding="utf-8"))
            lean_stack = json.loads((root / "minimal/compose.json").read_text(encoding="utf-8"))
            self.assertEqual(set(full_stack["services"]) - set(lean_stack["services"]),
                             {"edge", "allocation-service-cache", "allocation-service-mq"})
            self.assertEqual(set(lean_stack["services"]),
                             {"nacos", "allocation-service-db", "allocation-service", "catalog-service"})
            self.assertNotIn("ports", lean_stack["services"]["allocation-service"])
            for tree in (root / "full", root / "minimal"):
                self.assertEqual(len(list((tree / "ops/nacos").glob("*.json"))), 2)
                self.assertFalse((tree / ".env").exists())
                self.assertEqual(list(tree.rglob("ReservationMapper.java")), [])
            lean_pom = (root / "minimal/allocation-service/pom.xml").read_text(encoding="utf-8")
            for dependency in ("sentinel-core", "starter-amqp", "starter-data-redis", "starter-openfeign"):
                self.assertNotIn(dependency, lean_pom)
            self.assertIn("nacos-client", lean_pom)
            self.assertTrue(all("@sha256:" in spec["image"] for spec in full_stack["services"].values()
                                if "image" in spec))

    def test_gateway_and_feign_are_independent_selections(self):
        project, tasks = GENERATOR["normalize"](example("service-routing.json"))
        self.assertEqual(project["ingress"], "gateway")
        self.assertEqual(project["modules"][0]["policy"]["maxInFlight"], 32)
        self.assertEqual(project["modules"][1]["policy"]["parameters"], {"catalogName": "fixture-catalog"})
        self.assertTrue(all(path.startswith(("catalog-service/", "facade-service/"))
                            for task in tasks for path in task["write"]))
        with TemporaryDirectory() as folder:
            root = Path(folder)
            GENERATOR["render_tree"](project, root)
            self.assertIn("gateway-server-webmvc", (root / "edge-gateway/pom.xml").read_text(encoding="utf-8"))
            self.assertIn("starter-openfeign", (root / "facade-service/pom.xml").read_text(encoding="utf-8"))
            self.assertNotIn("starter-openfeign", (root / "catalog-service/pom.xml").read_text(encoding="utf-8"))
            self.assertFalse((root / "ops/nginx.conf").exists())

    def test_invalid_configuration_and_conflicting_paths_fail_early(self):
        manifest = example("reservations.json")
        for updates, message in (
            ({"features": ["rabbitmq"]}, "outbox needs PostgreSQL"),
            ({"policy": {"maxInFlight": True}}, "maxInFlight"),
            ({"policy": {"circuitMinRequests": 2}}, "both circuit fields"),
            ({"policy": {"parameters": {"catalogName": False}}}, "module parameter text"),
            ({"routes": ["/api/catalog/nested"]}, "nonoverlapping"),
        ):
            with self.subTest(updates=updates):
                invalid = deepcopy(manifest)
                invalid["modules"][0].update(updates)
                with self.assertRaisesRegex(ValueError, message):
                    GENERATOR["normalize"](invalid)
        task = {**manifest["tasks"][0], "id": "DUPLICATE", "files": ["reservationmapper.java"]}
        with self.assertRaisesRegex(ValueError, "one owner"):
            GENERATOR["normalize"]({**manifest, "tasks": [*manifest["tasks"], task]})
        task["package"] = "runtime"
        with self.assertRaisesRegex(ValueError, "trusted baseline"):
            GENERATOR["normalize"]({**manifest, "tasks": [task]})
