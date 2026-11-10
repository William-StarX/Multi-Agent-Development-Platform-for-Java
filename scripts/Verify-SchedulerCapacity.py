"""Measure the local control center on jdda_test using only simulated workers."""

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request


def latency(samples):
    ordered = sorted(samples)
    return {
        "requests": len(samples),
        "p95Ms": round(ordered[math.ceil(.95 * len(samples)) - 1], 2),
        "p99Ms": round(ordered[math.ceil(.99 * len(samples)) - 1], 2),
        "maxMs": round(ordered[-1], 2),
    }


def measure(call):
    start = time.perf_counter()
    try:
        value = call()
        return (time.perf_counter() - start) * 1000, value, None
    except urllib.error.HTTPError as error:
        failure = f"HTTP {error.code}"
    except (OSError, ValueError, KeyError, TypeError, AssertionError) as error:
        failure = type(error).__name__
    return (time.perf_counter() - start) * 1000, None, failure


def wave(inputs, request):
    begin = threading.Event()

    def simultaneous(item):
        begin.wait()
        return measure(lambda: request(item))

    with ThreadPoolExecutor(max_workers=len(inputs)) as pool:
        futures = [pool.submit(simultaneous, item) for item in inputs]
        begin.set()
        return [future.result() for future in futures]


def summary(results):
    stats = latency([row[0] for row in results])
    stats["failures"] = sum(row[2] is not None for row in results)
    stats["errors"] = sorted({row[2] for row in results if row[2]})
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workers", type=int, default=100)
    parser.add_argument("--tasks", type=int, default=1000)
    parser.add_argument("--psql", default=shutil.which("psql"))
    args = parser.parse_args()
    if args.workers < 1 or args.tasks < args.workers:
        parser.error("Pass a positive worker count and at least as many tasks.")
    if not args.psql or not os.environ.get("JDDA_DB_PASSWORD"):
        parser.error("Set JDDA_DB_PASSWORD for jdda_test and put psql on PATH or pass --psql.")

    root = Path(__file__).resolve().parent.parent
    jar = root / "platform/control-center/target/control-center-0.1.0-SNAPSHOT.jar"
    java = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java") \
        if "JAVA_HOME" in os.environ else shutil.which("java")
    if not java or not jar.is_file():
        parser.error("Build the control-center JAR and configure Java.")

    project = "capacity-" + secrets.token_hex(8)
    run = root / "target" / "scheduler-capacity" / project
    run.mkdir(parents=True)
    secret = secrets.token_urlsafe(32)
    database = "jdda_test"
    database_user = "jdda_local"
    jdbc = f"jdbc:postgresql://127.0.0.1:5432/{database}"
    http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    scope = f"/projects/{project}"
    base = None

    def request(method, path, token=secret, body=None, row=None):
        headers = {"Authorization": "Bearer " + token}
        if method != "GET":
            headers.update({"Content-Type": "application/json", "Idempotency-Key": secrets.token_hex(16)})
        if row is not None:
            headers["If-Match"] = f'"{row}"'
        payload = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(base + path, payload, headers, method=method)
        with http.open(req, timeout=35) as response:
            return response.status, json.load(response) if response.status != 204 else None

    log = run / "control.log"
    with log.open("wb") as output:
        proc = subprocess.Popen(
            [str(java), "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=0",
             f"--spring.datasource.url={jdbc}", f"--spring.datasource.username={database_user}"],
            stdout=output, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            env=os.environ | {"JDDA_ADMIN_TOKEN": secret},
        )
    provisioned = False
    try:
        deadline = time.monotonic() + 60
        while proc.poll() is None and time.monotonic() < deadline:
            match = re.search(r"Tomcat started on port (\d+)", log.read_text(encoding="utf-8", errors="replace"))
            if match:
                base = f"http://127.0.0.1:{match[1]}/api/v1"
                break
            time.sleep(.25)
        if not base:
            raise RuntimeError(f"Test control service did not start. See {log}")

        request("POST", "/projects", body={"projectId": project})
        provisioned = True
        _, pause = request("PUT", scope + "/dispatch", body={"paused": True}, row=1)
        workers = {}
        for agent, parent, role in [("middle", "root", "MANAGER"), ("scope", "middle", "MANAGER")] + [
                (f"worker-{i}", "scope", "WORKER") for i in range(args.workers)]:
            worker = role == "WORKER"
            request("POST", scope + "/agents", body={
                "schemaVersion": "1", "projectId": project, "agentId": agent, "parentId": parent,
                "roles": [role], "hostId": f"{project}-{agent}" if worker else None,
                "capabilities": ["java", "maven"] if worker else [], "endpoint": None,
            })
            if worker:
                workers[agent] = secrets.token_urlsafe(32)
                request("POST", f"{scope}/agents/{agent}/credentials", body={"token": workers[agent]})
        topology = request("GET", scope)[1]["topologyVersion"]
        fixture = json.loads((root / "contracts/examples.json").read_text(encoding="utf-8"))["TaskPackage"]
        for start in range(0, args.tasks, 16):
            tasks = [fixture | {
                "projectId": project, "taskId": f"T{i:06}", "parentTaskId": None, "managerId": "scope",
                "topologyVersion": topology, "dependencies": [], "ownedPaths": [f"bench/jobs/{i:06}.java"],
            } for i in range(start, min(start + 16, args.tasks))]
            status, batch = request("POST", scope + "/task-batches", body={"tasks": tasks})
            assert status == 201 and len(batch["tasks"]) == len(tasks)
            assert all(item["state"] == "READY" for item in batch["tasks"])
        assert request("POST", scope + "/worker/claims", token=next(iter(workers.values())), body={})[0] == 204
        _, resumed = request("PUT", scope + "/dispatch", body={"paused": False}, row=pause["rowVersion"])
        assert not resumed["paused"]

        def claim(token):
            status, body = request("POST", scope + "/worker/claims", token=token, body={})
            assert status == 200 and body["lease"]["status"] == "ACTIVE"
            return body, token

        def heartbeat(item):
            claim_body, token = item
            attempt = claim_body["attempt"]
            status, lease = request("POST", f"{scope}/attempts/{attempt['attemptId']}/heartbeat", token=token,
                                    body={"revision": attempt["revision"], "leaseEpoch": attempt["leaseEpoch"]})
            assert status == 200 and lease["status"] == "ACTIVE" and lease["leaseEpoch"] == attempt["leaseEpoch"]
            return lease["attemptId"]

        metadata = [measure(lambda: request("GET", scope + "/tasks/T000000/revisions/1")) for _ in range(60)]
        metadata += [measure(lambda: request("GET", scope)) for _ in range(30)]
        claims = wave(list(workers.values()), claim)
        accepted = [row[1] for row in claims if row[2] is None]
        beats = wave(accepted, heartbeat) if accepted else []
        duplicate_tasks = len(accepted) - len({item[0]["task"]["taskId"] for item in accepted})
        duplicate_hosts = len(accepted) - len({item[0]["attempt"]["hostId"] for item in accepted})
        report = {
            "project": project, "database": database, "workers": args.workers, "readyTasks": args.tasks,
            "metadataSequential": summary(metadata), "concurrentClaims": summary(claims),
            "concurrentHeartbeats": summary(beats) if beats else None,
            "duplicateTaskClaims": duplicate_tasks, "duplicateHostClaims": duplicate_hosts,
            "thresholdsMs": {"metadataP95": 500, "claimP95": 5000},
        }
        report["passed"] = (
            len(accepted) == args.workers and not duplicate_tasks and not duplicate_hosts
            and all(row[2] is None for row in metadata + beats)
            and report["metadataSequential"]["p95Ms"] < 500 and report["concurrentClaims"]["p95Ms"] < 5000
        )
        evidence = run / "report.json"
        evidence.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        print("Evidence:", evidence)
        return 0 if report["passed"] else 1
    finally:
        if proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        if provisioned:
            if not re.fullmatch(r"capacity-[a-f0-9]{16}", project):
                raise RuntimeError("Unexpected test project ID.")
            sql = (
                f"BEGIN; DELETE FROM jdda.request_receipt WHERE project_id='{project}'; "
                f"DELETE FROM jdda.task_record WHERE project_id='{project}'; "
                f"DELETE FROM jdda.agent WHERE project_id='{project}'; "
                f"DELETE FROM jdda.project WHERE project_id='{project}'; COMMIT;"
            )
            subprocess.run([args.psql, "-X", "-w", "-h", "127.0.0.1", "-p", "5432", "-U", database_user,
                            "-d", database, "-v", "ON_ERROR_STOP=1", "-q", "-c", sql], timeout=30, check=True,
                           env=os.environ | {"PGHOSTADDR": "127.0.0.1", "PGPASSWORD": os.environ["JDDA_DB_PASSWORD"]})


if __name__ == "__main__":
    raise SystemExit(main())
