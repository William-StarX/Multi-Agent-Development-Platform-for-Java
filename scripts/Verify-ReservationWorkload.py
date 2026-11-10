"""Trusted acceptance workload for the reservation example. Loaded by Verify-GeneratedProject."""
from concurrent.futures import ThreadPoolExecutor
import math
import time
import uuid


SERVICE = "allocation-service"


def percentile(samples, fraction):
    return round(sorted(samples)[math.ceil(len(samples) * fraction) - 1], 2)


def verify(deployment, request, eventually, require):
    module = next(m for m in deployment.project["modules"] if m["name"] == SERVICE)
    require(module["replicas"] == 2 and "postgres" in module["features"],
            "The reservation gate requires two allocation replicas and PostgreSQL.")
    features = set(module["features"])
    urls = deployment.endpoints(SERVICE)
    token = deployment.write_token(SERVICE)

    def read(path, index=0):
        return request(urls[index % len(urls)] + path)

    def reserve(resource, key, units=1, index=0, authorized=True):
        headers = {"Idempotency-Key": key}
        if authorized:
            headers["Authorization"] = "Bearer " + token
        return request(urls[index % len(urls)] + "/api/allocations", "POST",
                       {"resourceId": resource, "units": units}, headers)

    def parallel(count, threads, operation):
        def measure(index):
            started = time.perf_counter()
            value = operation(index)
            return (time.perf_counter() - started) * 1000, value
        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=threads) as pool:
            results = list(pool.map(measure, range(count)))
        return results, time.perf_counter() - started

    def remaining(resource):
        status, data, _ = read("/api/capacity/" + resource)
        require(status == 200 and data, "The capacity read failed.")
        return data["remaining"]

    unauthorized = reserve("primary", "no-auth-000", authorized=False)[0]
    require(unauthorized == 401, f"Expected HTTP 401 for an unauthenticated mutation, got {unauthorized}.")
    require(remaining("primary") == 100, "An unauthenticated mutation changed the resource.")
    allocations, seconds = parallel(256, 64, lambda i: reserve("primary", f"unique-{i:05}", index=i))
    responses = [row[1] for row in allocations]
    created = [data for status, data, _ in responses if status == 201]
    require(len(created) == 100 and all(status == 201 or (status == 409 and data and data.get("code") == "INSUFFICIENT")
                                        for status, data, _ in responses),
            f"Unexpected hot-key result distribution: {[(code, sum(r[0] == code for r in responses)) for code in sorted({r[0] for r in responses})]}.")
    require(len({row["reservationId"] for row in created}) == 100 and remaining("primary") == 0,
            "The hot resource oversold or returned duplicate IDs.")
    instances = {headers.get("X-JDDA-Instance") for _, _, headers in responses}
    require(None not in instances and len(instances) == 2, "Traffic did not reach both identical replicas.")
    samples = [row[0] for row in allocations]
    p95 = percentile(samples, .95)
    require(p95 < 2500, f"Hot-resource P95 {p95} ms exceeds the 2500 ms fixture budget.")

    same, _ = parallel(64, 32, lambda i: reserve("spare", "same-key-001", index=i))
    repeated = [row[1] for row in same]
    require(sum(status == 201 for status, _, _ in repeated) == 1
            and all(status in (200, 201) for status, _, _ in repeated)
            and len({data["reservationId"] for _, data, _ in repeated}) == 1 and remaining("spare") == 99,
            "Concurrent retries did not commit exactly one reservation.")
    key_conflict = reserve("spare", "same-key-001", units=2)
    require(key_conflict[0] == 409 and key_conflict[1]["code"] == "KEY_REUSED" and remaining("spare") == 99,
            "Conflicting reuse of an idempotency key changed capacity.")
    require(deployment.sql(SERVICE, """
            SELECT count(*) FROM allocation_request WHERE status='ALLOCATED'
            """) == "101" and deployment.sql(SERVICE, """
            SELECT count(*) FROM allocation_request WHERE status='PENDING'
            """) == "0", "The database ledger disagrees with API responses.")

    initial = deployment.initial_policy(SERVICE)
    policy_seen = set()

    def publish_and_wait(policy):
        digest = deployment.publish(SERVICE, policy)
        policy_seen.clear()

        def observed():
            for i in range(6):
                status, _, headers = read("/api/capacity/spare", i)
                if status in (200, 429) and headers.get("X-JDDA-Config") == digest:
                    policy_seen.add(headers.get("X-JDDA-Instance"))
            return len(policy_seen) == 2

        eventually(observed, "Policy revision on both replicas")
        return digest

    # The cap is intentionally separate from Sentinel QPS and applies in both presets.
    publish_and_wait({**initial, "maxInFlight": 1})
    bounded, _ = parallel(64, 48, lambda i: read("/api/capacity/spare", i))
    limited = sum(status == 429 and data is not None and data.get("code") == "CONCURRENCY_LIMIT"
                  and headers.get("Retry-After") == "1" for _, (status, data, headers) in bounded)
    require(limited > 0 and all(status in (200, 429) for _, (status, _, _) in bounded),
            "The generated application's in-flight policy did not bound the request burst.")
    restored = publish_and_wait(initial)
    deployment.publish(SERVICE, {**initial, "maxInFlight": 0})

    def rejected():
        logs = deployment.compose("logs", "--no-color", "--tail", "70", SERVICE)
        return logs.count("Rejected invalid Nacos policy") >= 2

    eventually(rejected, "Invalid Nacos policy rejection on both replicas")
    require(all(read("/api/capacity/spare", i)[2].get("X-JDDA-Config") == restored for i in range(4)),
            "An invalid Nacos update replaced the last good policy.")
    publish_and_wait(initial)
    optional = {}

    if "redis" in features:
        require(read("/api/allocations/catalog")[0] == 200, "Catalog did not fill the cache.")
        require(deployment.compose("exec", "-T", SERVICE + "-cache", "redis-cli", "EXISTS", SERVICE + ":catalog") == "1",
                "The catalog response was not cached.")
        deployment.compose("stop", SERVICE + "-cache")
        try:
            status, data, _ = read("/api/allocations/catalog")
            require(status == 200 and data["name"] == "Capacity catalog", "Redis failure broke the authoritative read.")
        finally:
            deployment.compose("start", SERVICE + "-cache")
        optional["redisCacheAndOutageFallback"] = True

    if "rabbitmq" in features:
        eventually(lambda: deployment.sql(SERVICE, "SELECT count(*) FROM jdda_delivered_event") == "101",
                   "Committed allocations delivered to RabbitMQ consumer", timeout=75)
        deployment.compose("stop", SERVICE + "-mq")
        try:
            status, data, _ = reserve("spare", "broker-offline-001")
            require(status == 201 and remaining("spare") == 98, "Broker outage prevented a durable allocation.")
            event = str(uuid.UUID(data["reservationId"]))
            require(deployment.sql(SERVICE, f"SELECT count(*) FROM jdda_outbox WHERE event_id='{event}' AND published_at IS NULL") == "1",
                    "The committed event was lost instead of being retained in the outbox.")
        finally:
            deployment.compose("start", SERVICE + "-mq")

        def delivered():
            return deployment.sql(SERVICE, f"SELECT count(*) FROM jdda_delivered_event WHERE event_id='{event}'") == "1"

        eventually(delivered, "Outbox event recovery", timeout=90)
        eventually(lambda: deployment.sql(SERVICE, f"SELECT count(*) FROM jdda_outbox WHERE event_id='{event}' AND published_at IS NOT NULL") == "1",
                   "Publisher confirm", timeout=40)
        deployment.sql(SERVICE, f"UPDATE jdda_outbox SET published_at=NULL WHERE event_id='{event}'")
        eventually(lambda: deployment.sql(SERVICE, f"SELECT count(*) FROM jdda_outbox WHERE event_id='{event}' AND published_at IS NOT NULL") == "1",
                   "Replay publish confirm", timeout=40)
        eventually(lambda: deployment.compose("exec", "-T", SERVICE + "-mq", "rabbitmqctl",
                   "-q", "list_queues", "name", "messages").find(SERVICE + ".events\t0") >= 0,
                   "Consumer acknowledgement after replay", timeout=45)
        require(delivered() and deployment.sql(SERVICE, "SELECT count(*) FROM jdda_delivered_event") == "102",
                "A replayed broker delivery produced a duplicate effect.")
        optional["rabbitOutageRecoveryAndReplay"] = True

    if "sentinel" in features:
        publish_and_wait({**initial, "apiQps": 1})
        for i in range(6):
            read("/api/capacity/spare", i)
        burst, _ = parallel(32, 16, lambda i: read("/api/capacity/spare", i))
        rate_rejects = sum(status == 429 and data is not None and data.get("code") == "TRAFFIC_LIMIT"
                           and headers.get("Retry-After") == "1" for _, (status, data, headers) in burst)
        require(rate_rejects > 0, "Sentinel did not enforce the Nacos QPS rule.")
        optional["sentinelRateRejected"] = rate_rejects
        publish_and_wait({**initial, "cacheTtlSeconds": 0, "circuitMinRequests": 2, "circuitOpenSeconds": 4})
        deployment.compose("stop", "catalog-service")
        try:
            def opened():
                for i in range(8):
                    status, data, _ = read("/api/allocations/catalog", i)
                    if status == 503 and data and data.get("code") == "UPSTREAM_CIRCUIT_OPEN":
                        return True
                return False
            eventually(opened, "Sentinel circuit opening", timeout=15)
        finally:
            deployment.compose("start", "catalog-service")
        eventually(lambda: read("/api/allocations/catalog")[0] == 200, "Upstream recovery after circuit open", timeout=45)
        publish_and_wait(initial)
        optional["sentinelCircuitOpenedAndRecovered"] = True

    if deployment.project["ingress"] == "nginx":
        stopped = deployment.instances(SERVICE)[0]
        deployment.docker("stop", stopped)
        try:
            started = time.monotonic()
            stable_reads = failed_probes = 0

            def converged():
                nonlocal stable_reads, failed_probes
                try:
                    status, _, headers = read("/api/capacity/spare")
                    healthy = status == 200 and headers.get("X-JDDA-Instance") not in (None, stopped[:12])
                except OSError:
                    healthy = False
                stable_reads = stable_reads + 1 if healthy else 0
                failed_probes += int(not healthy)
                return stable_reads == 12

            eventually(converged, "Nginx replica failover with twelve consecutive successful reads", timeout=15)
            optional["nginxFailoverStableMs"] = round((time.monotonic() - started) * 1000, 2)
            optional["nginxFailedProbesDuringFailover"] = failed_probes
        finally:
            deployment.docker("start", stopped)
        optional["nginxReplicaFailover"] = True

    return {"requests": len(allocations), "parallelism": 64, "created": len(created),
            "insufficient": len(responses) - len(created), "unexpectedFailures": 0,
            "replicasUsed": len(instances), "idempotencyRequests": len(repeated), "duplicateAllocations": 0,
            "p95Ms": p95, "p99Ms": percentile(samples, .99), "requestsPerSecond": round(len(allocations) / seconds, 1),
            "budgetP95Ms": 2500, "authenticationGate": True, "hotReloadAndRollback": True,
            "invalidPolicyRetained": True, "inFlightRejected": limited, "optional": optional}
