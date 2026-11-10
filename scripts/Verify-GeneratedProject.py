"""Build and verify an exact Library candidate in an isolated Docker Compose stack."""
import argparse
import base64
import hashlib
from io import BytesIO
from ipaddress import IPv4Address
import json
import os
from pathlib import Path, PurePosixPath
import re
import runpy
import secrets
import stat
import subprocess
from zipfile import ZipFile

from ProjectNacos import ProjectNacos, eventually, request, require

ROOT = Path(__file__).resolve().parents[1]
SHA = re.compile(r"[0-9a-f]{40}")


def git(repository, *args):
    result = subprocess.run(["git", "-C", str(repository), "-c", "core.hooksPath=/dev/null", *args],
                            capture_output=True, check=True, timeout=60)
    return result.stdout


def archive(repository, sha):
    require(isinstance(sha, str) and SHA.fullmatch(sha), "Expected an exact Git SHA-1.")
    with ZipFile(BytesIO(git(repository, "archive", "--format=zip", sha))) as bundle:
        paths = {}
        for entry in bundle.infolist():
            if entry.is_dir():
                continue
            path = PurePosixPath(entry.filename)
            require(not path.is_absolute() and ".." not in path.parts and ":" not in entry.filename
                    and "\\" not in entry.filename and not stat.S_ISLNK(entry.external_attr >> 16),
                    "The candidate archive contains a special or unsafe path.")
            require(entry.file_size <= 1024 * 1024, "The candidate contains an oversized file.")
            paths[entry.filename] = bundle.read(entry)
        return paths


def validate_candidate(report, manifest_file, work):
    manifest_bytes = manifest_file.read_bytes()
    require(hashlib.sha256(manifest_bytes).hexdigest() == report.get("manifestSha256"),
            "Manifest differs from the approved generation input.")
    generator = runpy.run_path(str(ROOT / "scripts/Generate-Project.py"))
    expected, tasks = generator["normalize"](json.loads(manifest_bytes.decode("utf-8-sig")), report["minimal"])
    baseline = work / "expected"
    baseline.mkdir()
    generator["render_tree"](expected, baseline)
    expected_files = {path.relative_to(baseline).as_posix(): path.read_bytes()
                      for path in baseline.rglob("*") if path.is_file()}

    repo = Path(report["candidateRepository"]).resolve()
    base, candidate = report["baseSha"], report["candidateSha"]
    require(archive(repo, base) == expected_files, "The Git baseline differs from the trusted renderer.")
    content = archive(repo, candidate)
    require(all(content.get(path) == value for path, value in expected_files.items()),
            "The candidate changed trusted build, runtime or acceptance files.")
    expected_changes = {path for task in tasks for path in task["write"]}
    require(set(content) - set(expected_files) == expected_changes, "Candidate sources differ from approved file scopes.")
    require(set(report["changedPaths"]) == expected_changes, "Library changed paths differ from the approved file scopes.")
    workers = report["tasks"]
    require(len(workers) == len(tasks), "The report must include all scoped Worker results.")
    predecessor = candidate
    for spec, result in reversed(list(zip(tasks, workers))):
        require(result["state"] == "SUCCEEDED" and result["baseCommit"] == base
                and result["taskId"].startswith(spec["task"] + "-") and result["worker"] == spec["worker"],
                "Worker result identity differs from the approved plan.")
        commit = result["resultCommit"]
        require(SHA.fullmatch(commit), "Missing exact Worker commit.")
        require(git(repo, "rev-list", "--parents", "-n", "1", commit).decode().split() == [commit, base],
                "Worker result does not descend directly from the pinned base.")
        additions = git(repo, "diff-tree", "--no-commit-id", "--name-status", "--no-renames", "-r", "-z",
                        commit).decode().split("\0")[:-1]
        require(len(additions) == 2 * len(spec["write"]) and set(additions[::2]) == {"A"}
                and set(additions[1::2]) == set(spec["write"]), "Worker commit differs from its own approved file scope.")
        parents = git(repo, "rev-list", "--parents", "-n", "1", predecessor).decode().split()
        require(len(parents) == 3 and parents[2] == commit,
                "The Library candidate does not preserve the ordered Worker commit graph.")
        predecessor = parents[1]
    require(predecessor == base, "The candidate merge chain does not terminate at the pinned base.")
    checkout = work / "checkout"
    for relative, value in content.items():
        path = checkout / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(value)
    return checkout


class Deployment:
    def __init__(self, directory, candidate, log):
        self.directory = directory
        self.project = json.loads((directory / "jdda-project.json").read_text(encoding="utf-8"))
        self.stack = "jdda-verify-" + secrets.token_hex(6)
        self.log = log
        compose = (directory / "compose.json").read_text(encoding="utf-8")
        self.env = {k: v for k, v in os.environ.items()
                    if not k.upper().startswith(("JDDA_", "DEEPSEEK_", "NACOS_", "SPRING_", "PGPASSWORD", "COMPOSE_"))}
        for variable in set(re.findall(r"\$\{([A-Z0-9_]+):\?", compose)):
            self.env[variable] = secrets.token_hex(24)
        self.env["NACOS_AUTH_TOKEN"] = base64.b64encode(secrets.token_bytes(48)).decode()
        self.env["JDDA_SOURCE_SHA"] = candidate
        self.admin_password = secrets.token_hex(24)
        self.candidate = candidate
        self.nacos = None
        self.probe = None
        self.probe_ports = {}

    def docker(self, *arguments, timeout=120):
        call = subprocess.run(["docker", *arguments], cwd=self.directory, env=self.env, timeout=timeout,
                              capture_output=True, text=True, encoding="utf-8", errors="replace")
        if call.returncode != 0:
            with self.log.open("a", encoding="utf-8") as log:
                log.write(call.stdout + call.stderr + "\n")
            raise RuntimeError(f"Docker exited with {call.returncode}. See {self.log}.")
        return call.stdout.strip()

    def compose(self, *arguments, timeout=120):
        files = ["-f", "compose.json"]
        if self.probe:
            files += ["-f", str(self.probe)]
        return self.docker("compose", "-p", self.stack, *files, *arguments, timeout=timeout)

    def port(self, service, internal, index=1):
        address = self.compose("port", "--index", str(index), service, str(internal))
        require(re.fullmatch(r"127\.0\.0\.1:[0-9]+", address), "Expected a loopback-published service port.")
        return "http://" + address

    def publish(self, service, config):
        return self.nacos.publish(service, config)

    def start(self):
        self.compose("up", "-d", "nacos")
        self.nacos = ProjectNacos(self.port("nacos", 8848), self.project["project"])
        policies = {module["name"]: self.initial_policy(module["name"]) for module in self.project["modules"]}
        passwords = {name: self.env[name.upper().replace("-", "_") + "_NACOS_PASSWORD"] for name in policies}
        self.nacos.initialize(self.admin_password, policies, passwords)
        self.compose("build", timeout=600)
        self.compose("up", "-d", "--no-build", "--wait", "--wait-timeout", "150", timeout=200)
        if self.project["ingress"] == "direct":
            self.start_direct_probe()

    def start_direct_probe(self):
        # Compose internal networks do not publish host ports. The verifier alone
        # bridges loopback traffic to exact replica addresses for the load test.
        servers = []
        for module in self.project["modules"]:
            name = module["name"]
            instances = json.loads(self.docker("inspect", *self.instances(name)))
            self.probe_ports[name] = []
            for instance in instances:
                ip = IPv4Address(instance["NetworkSettings"]["Networks"][self.stack + "_runtime"]["IPAddress"])
                port = 9000 + len(servers)
                self.probe_ports[name].append(port)
                servers.append(f"server {{ listen {port}; location / {{ proxy_pass http://{ip}:8080; }} }}")
        configuration = self.log.with_name("direct-probe.conf")
        configuration.write_text("\n".join(servers) + "\n", encoding="utf-8")
        self.probe = self.log.with_name("direct-probe.compose.json")
        self.probe.write_text(json.dumps({"services": {"verify-probe": {
            "image": self.project["images"]["nginx"], "platform": "linux/amd64",
            "networks": ["runtime", "operator"],
            "ports": [f"127.0.0.1::{port}" for ports in self.probe_ports.values() for port in ports],
            "volumes": [{"type": "bind", "source": str(configuration),
                         "target": "/etc/nginx/conf.d/default.conf", "read_only": True}],
        }}}), encoding="utf-8")
        self.compose("up", "-d", "--no-deps", "verify-probe")
        for module in self.project["modules"]:
            for address in self.endpoints(module["name"]):
                eventually(lambda: request(address + "/actuator/health")[0] == 200, "Direct replica probe")

    def initial_policy(self, service):
        return json.loads((self.directory / "ops/nacos" / (service + ".json")).read_text(encoding="utf-8"))

    def instances(self, service):
        return self.compose("ps", "-q", service).splitlines()

    def endpoints(self, service):
        if self.project["ingress"] != "direct":
            return [self.port("edge", 8080)]
        return [self.port("verify-probe", port) for port in self.probe_ports[service]]

    def verify_images(self):
        checked = {}
        modules = {module["name"]: module["replicas"] for module in self.project["modules"]}
        if self.project["ingress"] == "gateway":
            modules["edge"] = 1
        for name, replicas in modules.items():
            containers = self.instances(name)
            require(len(containers) == replicas, "The requested service replica count is not running.")
            records = json.loads(self.docker("inspect", *containers))
            image_ids = set()
            networks = {self.stack + "_runtime"}
            if name == "edge":
                networks.add(self.stack + "_operator")
            for item in records:
                host, config = item["HostConfig"], item["Config"]
                require(config["Labels"].get("org.opencontainers.image.revision") == self.candidate
                        and config["User"] == "10001:10001" and host["ReadonlyRootfs"] and not host["Privileged"]
                        and "ALL" in host["CapDrop"] and item["State"]["Health"]["Status"] == "healthy"
                        and set(item["NetworkSettings"]["Networks"]) == networks,
                        "A running replica differs from the pinned or isolated configuration.")
                image_ids.add(item["Image"])
            require(len(image_ids) == 1, "Replicas do not use the same image.")
            checked[name] = {"replicas": len(containers), "image": next(iter(image_ids))}
        internal = self.docker("network", "inspect", "--format", "{{.Internal}}", self.stack + "_runtime")
        require(internal == "true", "The application network is not isolated.")
        return checked

    def write_token(self, service):
        return self.env[service.upper().replace("-", "_") + "_WRITE_TOKEN"]

    def sql(self, service, statement):
        return self.compose("exec", "-T", service + "-db", "psql", "-U", "app", "-d", "app",
                            "-At", "-v", "ON_ERROR_STOP=1", "-c", statement)

    def close(self):
        self.compose("down", "--volumes", "--remove-orphans", timeout=90)


def promote(report):
    repository = Path(report["repository"])
    require(git(repository, "status", "--porcelain").strip() == b""
            and git(repository, "symbolic-ref", "HEAD").decode().strip() == "refs/heads/main"
            and git(repository, "rev-parse", "HEAD").decode().strip() == report["baseSha"],
            "The generated target must still be clean and at the approved baseline.")
    git(repository, "fetch", "--no-tags", "--", report["candidateRepository"], "refs/heads/candidate")
    require(git(repository, "rev-parse", "FETCH_HEAD").decode().strip() == report["candidateSha"],
            "The Library candidate changed after verification.")
    git(repository, "merge", "--ff-only", report["candidateSha"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generation", required=True, type=Path)
    parser.add_argument("--manifest", required=True, type=Path)
    suite = parser.add_mutually_exclusive_group(required=True)
    suite.add_argument("--suite", choices=("smoke", "reservations"))
    suite.add_argument("--workload", type=Path, help="Operator-authored Python acceptance code outside the generated Git tree.")
    parser.add_argument("--promote", action="store_true")
    args = parser.parse_args()
    if args.promote and args.suite == "smoke":
        parser.error("Promotion requires a workload acceptance suite.")
    report = json.loads(args.generation.read_text(encoding="utf-8-sig"))
    work = args.generation.resolve().parent / ("verify-" + secrets.token_hex(5))
    work.mkdir()
    try:
        workload = args.workload.resolve() if args.workload else None
        if workload:
            untrusted = (Path(report["repository"]), Path(report["candidateRepository"]), args.generation.parent)
            require(not any(workload.is_relative_to(path.resolve()) for path in untrusted),
                    "Keep the operator acceptance script outside all generated trees and reports.")
        elif args.suite == "reservations":
            workload = ROOT / "scripts/Verify-ReservationWorkload.py"
        workload_sha = hashlib.sha256(workload.read_bytes()).hexdigest() if workload else None
        checkout = validate_candidate(report, args.manifest, work)
        deployment = Deployment(checkout, report["candidateSha"], work / "docker.log")
        try:
            print(f"Starting isolated stack {deployment.stack}", flush=True)
            deployment.start()
            images = deployment.verify_images()
            results = {}
            if workload:
                suite = runpy.run_path(str(workload))
                results = suite["verify"](deployment, request, eventually, require)
                require(isinstance(results, dict) and results, "The workload must return measured results.")
                require(hashlib.sha256(workload.read_bytes()).hexdigest() == workload_sha,
                        "The acceptance script changed during execution.")
        except BaseException:
            try:
                (work / "containers.log").write_text(deployment.compose("logs", "--no-color", "--tail", "100"), encoding="utf-8")
            except Exception:
                pass
            raise
        finally:
            deployment.close()
        if args.promote:
            promote(report)
        evidence = {"status": "PASSED", "suite": args.suite or "operator", "workloadSha256": workload_sha,
                    "candidateSha": report["candidateSha"],
                    "baseSha": report["baseSha"], "images": images, "workload": results, "promoted": args.promote}
        path = work / "report.json"
        path.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
        print(path)
        print(json.dumps(evidence, indent=2))
    except (RuntimeError, OSError, ValueError, subprocess.SubprocessError) as error:
        parser.exit(1, f"Verification failed: {error}\nEvidence directory: {work}\n")


if __name__ == "__main__":
    main()
