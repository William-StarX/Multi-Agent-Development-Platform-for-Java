"""Operator acceptance for the Gateway and OpenFeign preset."""


def verify(deployment, request, eventually, require):
    require(deployment.project["ingress"] == "gateway", "Expected the Spring Cloud Gateway preset.")
    address = deployment.endpoints("facade-service")[0]
    catalog_path = address + "/api/catalog"
    summary_path = address + "/api/summary"
    status, body, headers = request(summary_path)
    require(status == 200 and body == {"origin": "fixture-catalog"} and headers.get("X-JDDA-Config"),
            "The Gateway route did not reach the OpenFeign-backed service.")
    facade_revision = headers["X-JDDA-Config"]
    require(request(catalog_path)[1] == {"name": "fixture-catalog"}
            and request(address + "/api/unlisted")[0] == 404, "Gateway routed outside the declared paths.")

    initial = deployment.initial_policy("catalog-service")
    changed = {**initial, "maxInFlight": 7, "parameters": {"catalogName": "updated-catalog"}}
    revision = deployment.publish("catalog-service", changed)
    eventually(lambda: request(catalog_path)[2].get("X-JDDA-Config") == revision, "Catalog Nacos revision")
    status, body, headers = request(summary_path)
    require(status == 200 and body == {"origin": "updated-catalog"} and headers.get("X-JDDA-Config") == facade_revision,
            "A service parameter did not propagate or the update affected another service's policy.")

    deployment.compose("stop", "catalog-service")
    try:
        status, body, _ = request(summary_path)
        require(status == 503 and body == {"code": "CATALOG_UNAVAILABLE"},
                "OpenFeign did not surface an unavailable upstream within the Gateway deadline.")
    finally:
        deployment.compose("start", "catalog-service")
    eventually(lambda: request(summary_path)[0] == 200, "OpenFeign recovery", timeout=55)
    return {"gatewayRouteAllowlist": True, "openFeignHttpCall": True, "upstreamFailureAndRecovery": True,
            "perModuleConfigIsolation": True, "liveApplicationParameter": True}
