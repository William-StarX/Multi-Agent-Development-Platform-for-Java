"""Render a trusted Java 21 project baseline and exact Agent work scopes."""
import argparse
import json
from pathlib import Path
import re
from tempfile import TemporaryDirectory


TEMPLATES = Path(__file__).resolve().parents[1] / "templates/java21"
NAME = r"[a-z][a-z0-9]*(?:-[a-z0-9]+)*"
PACKAGE = r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*"
ROUTE = r"/api/[a-z][a-z0-9-]*(?:/[a-z][a-z0-9-]*)*"
FEATURES = {
    "postgres": [
        ("org.springframework.boot", "spring-boot-starter-jdbc", None),
        ("org.flywaydb", "flyway-core", "11.20.3"),
        ("org.flywaydb", "flyway-database-postgresql", "11.20.3"),
        ("org.postgresql", "postgresql", None),
    ],
    "redis": [("org.springframework.boot", "spring-boot-starter-data-redis", None)],
    "rabbitmq": [("org.springframework.boot", "spring-boot-starter-amqp", None)],
    "sentinel": [("com.alibaba.csp", "sentinel-core", "1.8.10")],
    "openfeign": [("org.springframework.cloud", "spring-cloud-starter-openfeign", "4.3.3")],
}
NACOS = ("com.alibaba.nacos", "nacos-client", "3.3.0")
GATEWAY = ("org.springframework.cloud", "spring-cloud-starter-gateway-server-webmvc", "4.3.5")
POLICY = {
    "maxInFlight": (64, 1, 1000),
    "apiQps": (0, 0, 10000),
    "cacheTtlSeconds": (0, 0, 3600),
    "circuitMinRequests": (0, 0, 1000),
    "circuitOpenSeconds": (0, 0, 300),
}
IMAGES = json.loads((TEMPLATES / "images.json").read_text(encoding="utf-8"))


def checked(value, pattern, label, size=80):
    if not isinstance(value, str) or len(value) > size or not re.fullmatch(pattern, value):
        raise ValueError(f"Invalid {label}.")
    return value


def fields(value, allowed, label):
    if not isinstance(value, dict) or value.keys() - set(allowed.split()):
        raise ValueError(f"Unknown or invalid fields in {label}.")
    return value


def sequence(value, minimum, maximum, label):
    if not isinstance(value, list) or not minimum <= len(value) <= maximum:
        raise ValueError(f"Expected {minimum} to {maximum} entries in {label}.")
    return value


def bounded(value, limit, label):
    if not isinstance(value, str) or not value.strip() or len(value) > limit or "\x00" in value:
        raise ValueError(f"Expected nonempty {label} up to {limit} characters.")
    return value


def policy_settings(value, features):
    raw = fields(value, " ".join(POLICY) + " parameters", "module policy")
    policy = {key: raw.get(key, default) for key, (default, _, _) in POLICY.items()}
    if "redis" in features and "cacheTtlSeconds" not in raw:
        policy["cacheTtlSeconds"] = 60
    for key, (_, minimum, maximum) in POLICY.items():
        if type(policy[key]) is not int or not minimum <= policy[key] <= maximum:
            raise ValueError(f"Expected {key} between {minimum} and {maximum}.")
    if bool(policy["circuitMinRequests"]) != bool(policy["circuitOpenSeconds"]):
        raise ValueError("Set both circuit fields to nonzero values or both to zero.")
    if "sentinel" not in features and any(policy[key] for key in ("apiQps", "circuitMinRequests")):
        raise ValueError("Request rate and circuit settings require Sentinel.")
    if "redis" not in features and policy["cacheTtlSeconds"]:
        raise ValueError("Cache TTL requires Redis.")
    if "parameters" in raw:
        parameters = raw["parameters"]
        if not isinstance(parameters, dict) or len(parameters) > 16:
            raise ValueError("Expected at most 16 module policy parameters.")
        for key, value in parameters.items():
            checked(key, r"[a-z][A-Za-z0-9_.-]{0,63}", "parameter name")
            if not isinstance(value, str) or len(value) > 256 or "\x00" in value:
                raise ValueError("Expected module parameter text up to 256 characters.")
        policy["parameters"] = parameters
        if len(json.dumps(policy, separators=(",", ":"))) > 4096:
            raise ValueError("Module policy exceeds 4096 characters.")
    return policy


def normalize(manifest, minimal=False):
    raw = fields(manifest, "schemaVersion project group ingress modules tasks", "project manifest")
    if type(raw.get("schemaVersion")) is not int or raw["schemaVersion"] != 1:
        raise ValueError("Expected project schemaVersion 1.")
    project = checked(raw.get("project"), NAME, "project name", 48)
    group = checked(raw.get("group"), PACKAGE, "Java group", 96)
    ingress = raw.get("ingress", "direct")
    if ingress not in ("direct", "nginx", "gateway"):
        raise ValueError("Ingress must be direct, nginx or gateway.")
    modules = {}
    routes = set()
    for raw_module in sequence(raw.get("modules"), 1, 8, "modules"):
        module = fields(raw_module, "name replicas features routes migration policy", "module")
        name = checked(module.get("name"), NAME + "-service", "service module", 48)
        if name in modules:
            raise ValueError("Service module names must be distinct.")
        replicas = module.get("replicas", 1)
        if type(replicas) is not int or not 1 <= replicas <= 8:
            raise ValueError("Replicas must be an integer from 1 to 8.")
        features = sequence(module.get("features", []), 0, len(FEATURES), "features")
        if any(not isinstance(f, str) or f not in FEATURES for f in features) or len(set(features)) != len(features):
            raise ValueError("Expected distinct known module features.")
        if "rabbitmq" in features and "postgres" not in features:
            raise ValueError("The RabbitMQ outbox needs PostgreSQL.")
        migration = module.get("migration")
        if migration is not None:
            bounded(migration, 64000, "migration")
            if "postgres" not in features:
                raise ValueError("A SQL migration needs PostgreSQL.")
        paths = [checked(p, ROUTE, "route", 120) for p in sequence(module.get("routes", []), 0, 12, "routes")]
        for path in paths:
            if any(path == route or path.startswith(route + "/") or route.startswith(path + "/") for route in routes):
                raise ValueError("Routes must be distinct and nonoverlapping.")
            routes.add(path)
        policy = policy_settings(module.get("policy", {}), features)
        if minimal:
            features = [f for f in features if f == "postgres"]
            policy = {key: policy[key] for key in policy if key in ("maxInFlight", "parameters")}
            policy = policy_settings(policy, features)
        package = group + "." + name.removesuffix("-service").replace("-", "_")
        modules[name] = {"name": name, "package": package, "replicas": replicas,
                         "features": sorted(features), "routes": paths, "migration": migration, "policy": policy}
    if ingress != "direct" and not routes:
        raise ValueError("A proxy ingress needs at least one explicit public route.")

    tasks = []
    owned = set()
    ids = set()
    for item in sequence(raw.get("tasks", []), 0, 16, "tasks"):
        task = fields(item, "id module package files goal constraints", "task")
        task_id = checked(task.get("id"), r"[A-Z][A-Z0-9]{0,23}", "task ID")
        name = task.get("module")
        if not isinstance(name, str) or name not in modules or task_id in ids:
            raise ValueError("Tasks must name a known module and a distinct ID.")
        ids.add(task_id)
        scope = checked(task.get("package"), PACKAGE, "package scope", 72)
        if "runtime" in scope.split("."):
            raise ValueError("The runtime package belongs to the trusted baseline.")
        package = modules[name]["package"] + "." + scope
        files = [checked(f, r"[A-Za-z][A-Za-z0-9_]*\.java", "Java source name") for f in
                 sequence(task.get("files"), 1, 12, "task files")]
        paths = [f"{name}/src/main/java/{package.replace('.', '/')}/{f}" for f in files]
        normalized_paths = {path.casefold() for path in paths}
        if len(normalized_paths) != len(paths) or normalized_paths & owned:
            raise ValueError("Each generated source path must have one owner.")
        owned.update(normalized_paths)
        scope_id = name + "-" + scope.replace(".", "-")
        if len(scope_id) > 115:
            raise ValueError("Combined module and package identifier is too long.")
        tasks.append({
            "task": task_id, "manager": "engineering", "scope": name, "worker": scope_id + "-worker",
            "capability": "java-" + scope_id, "policy": "generated-java-v1",
            "goal": bounded(task.get("goal"), 1200, "task goal"),
            "constraints": f"Java package {package}. " + bounded(task.get("constraints"), 12000, "constraints"),
            "write": paths, "read": [], "remove": [], "move": {},
        })
    return {"schemaVersion": 1, "project": project, "group": group,
            "ingress": "direct" if minimal else ingress, "modules": list(modules.values())}, tasks


def dump(path, obj):
    text(path, json.dumps(obj, indent=2, ensure_ascii=False) + "\n")


def text(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")


def template(source, **parameters):
    content = (TEMPLATES / source).read_text(encoding="utf-8")
    return re.sub(r"@([a-z]+)@", lambda m: parameters[m.group(1)], content)


def secret(name):
    return "$" + "{" + name + ":?provide " + name + "}"


def dependency(group, artifact, version):
    return ("        <dependency>\n"
            f"            <groupId>{group}</groupId>\n"
            f"            <artifactId>{artifact}</artifactId>\n"
            + (f"            <version>{version}</version>\n" if version else "")
            + "        </dependency>")


def application_container(name, replicas, env, networks, dependencies):
    health = "exec 3<>/dev/tcp/127.0.0.1/8080; printf 'GET /actuator/health HTTP/1.0\\r\\n\\r\\n' >&3; head -1 <&3 | grep -q ' 200 '"
    return {
        "build": {"context": ".", "args": {"MODULE": name, "SOURCE_SHA": "$" + "{JDDA_SOURCE_SHA:-local}"}},
        "scale": replicas, "environment": env, "networks": networks,
        "read_only": True, "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"],
        "tmpfs": ["/tmp:rw,nosuid,nodev,size=96m,mode=1777"], "mem_limit": "512m", "pids_limit": 200,
        "healthcheck": {"test": ["CMD", "bash", "-ec", health], "interval": "2s",
                        "timeout": "2s", "retries": 40, "start_period": "25s"},
        "depends_on": dependencies,
    }


def service_settings(module, group, stack):
    name = module["name"]
    features = module["features"]
    prefix = name.upper().replace("-", "_")
    env = {
        "NACOS_ADDRESS": "nacos:8848", "NACOS_GROUP": group, "NACOS_USERNAME": name,
        "NACOS_PASSWORD": secret(prefix + "_NACOS_PASSWORD"),
        "JDDA_WRITE_TOKEN": secret(prefix + "_WRITE_TOKEN"),
        "JAVA_TOOL_OPTIONS": "-XX:ActiveProcessorCount=2 -XX:MaxRAMPercentage=50 -Duser.home=/tmp -DJM.SNAPSHOT.PATH=/tmp/nacos",
    }
    config = {
        "spring": {"application": {"name": name}},
        "server": {"port": 8080, "tomcat": {"threads": {"max": 96}, "accept-count": 128}},
        "management": {"endpoints": {"web": {"exposure": {"include": "health"}}}},
    }
    service = application_container(name, module["replicas"], env, ["runtime"],
                                    {"nacos": {"condition": "service_started"}})
    if "postgres" in features:
        database = name + "-db"
        password = secret(prefix + "_DB_PASSWORD")
        stack["services"][database] = {
            "image": IMAGES["postgres"], "networks": ["runtime"],
            "environment": {"POSTGRES_DB": "app", "POSTGRES_USER": "postgres",
                            "POSTGRES_PASSWORD": secret(prefix + "_DB_ADMIN_PASSWORD"), "APP_DB_PASSWORD": password},
            "volumes": [f"{database}:/var/lib/postgresql", "./ops/postgres-init.sh:/docker-entrypoint-initdb.d/10-app.sh:ro"],
            "healthcheck": {"test": ["CMD", "pg_isready", "-h", "127.0.0.1", "-U", "postgres", "-d", "app"],
                            "interval": "2s", "timeout": "2s", "retries": 40},
        }
        stack["volumes"][database] = {}
        service["depends_on"][database] = {"condition": "service_healthy"}
        env.update(SPRING_DATASOURCE_URL=f"jdbc:postgresql://{database}:5432/app",
                   SPRING_DATASOURCE_USERNAME="app", SPRING_DATASOURCE_PASSWORD=password)
        config["spring"]["datasource"] = {"hikari": {"maximum-pool-size": 16, "connection-timeout": 2000}}
    if "redis" in features:
        cache = name + "-cache"
        stack["services"][cache] = {"image": IMAGES["redis"], "networks": ["runtime"]}
        env["SPRING_DATA_REDIS_HOST"] = cache
        config["spring"]["data"] = {"redis": {"connect-timeout": "350ms", "timeout": "350ms"}}
        config["management"]["health"] = {"redis": {"enabled": False}}
    if "rabbitmq" in features:
        broker = name + "-mq"
        password = secret(prefix + "_MQ_PASSWORD")
        stack["services"][broker] = {
            "image": IMAGES["rabbitmq"], "networks": ["runtime"],
            "environment": {"RABBITMQ_DEFAULT_USER": "app", "RABBITMQ_DEFAULT_PASS": password},
            "volumes": [f"{broker}:/var/lib/rabbitmq"],
            "healthcheck": {"test": ["CMD", "rabbitmq-diagnostics", "-q", "ping"],
                            "interval": "3s", "timeout": "5s", "retries": 40},
        }
        stack["volumes"][broker] = {}
        service["depends_on"][broker] = {"condition": "service_healthy"}
        env.update(SPRING_RABBITMQ_HOST=broker, SPRING_RABBITMQ_USERNAME="app", SPRING_RABBITMQ_PASSWORD=password)
        config["spring"]["rabbitmq"] = {
            "connection-timeout": "1500ms", "publisher-confirm-type": "correlated", "publisher-returns": True,
            "template": {"mandatory": True},
            "listener": {"simple": {"default-requeue-rejected": False, "prefetch": 16,
                                     "retry": {"enabled": True, "max-attempts": 3}}},
        }
        config["management"].setdefault("health", {})["rabbit"] = {"enabled": False}
    if "openfeign" in features:
        config["spring"]["cloud"] = {"openfeign": {"client": {"config": {
            "default": {"connectTimeout": 500, "readTimeout": 1000},
        }}}}
    return service, config


def add_gateway(directory, values, public, stack):
    name = "edge-gateway"
    text(directory / name / "pom.xml", template("service-pom.xml.in", **values, service=name,
                                               nacos="", dependencies=dependency(*GATEWAY)))
    package = values["group"] + ".gateway"
    source = directory / name / "src/main"
    java = source / "java" / package.replace(".", "/")
    text(java / "Application.java", template("runtime/Application.java.in", package=package))
    routes = [{"id": f"{module}-{index}", "uri": f"http://{module}:8080",
               "predicates": [f"Path={route}/**"], "filters": ["RequestSize=64KB"]}
              for module, spec in public.items() for index, route in enumerate(spec["routes"])]
    config = {
        "spring": {"application": {"name": name},
                   "cloud": {"gateway": {"server": {"webmvc": {"routes": routes}}}},
                   "http": {"client": {"connect-timeout": "500ms", "read-timeout": "4s"}}},
        "server": {"port": 8080},
    }
    dump(source / "resources/application.yml", config)
    stack["services"]["edge"] = application_container(
        name, 1, {"JAVA_TOOL_OPTIONS": "-XX:ActiveProcessorCount=2 -XX:MaxRAMPercentage=50 -Duser.home=/tmp"},
        ["runtime", "operator"], {module: {"condition": "service_healthy"} for module, spec in public.items()
                                  if spec["routes"]})
    stack["services"]["edge"]["ports"] = ["127.0.0.1::8080"]


def render_tree(project, directory):
    values = {key: project[key] for key in ("project", "group")}
    modules = project["modules"]
    names = [m["name"] for m in modules] + (["edge-gateway"] if project["ingress"] == "gateway" else [])
    text(directory / "pom.xml", template("pom.xml.in", **values,
         modules="\n".join(f"        <module>{name}</module>" for name in names)))
    text(directory / "Dockerfile", template("Dockerfile.in", **IMAGES))
    text(directory / ".gitignore", ".env\n.env.*\ntarget/\n.idea/\n*.log\n")
    text(directory / ".dockerignore", ".git\n.env*\n**/target\n.idea\n*.log\n")
    text(directory / ".gitattributes", "* text=auto eol=lf\n")

    nacos = {
        "image": IMAGES["nacos"], "networks": ["runtime", "operator"], "ports": ["127.0.0.1::8848"],
        "environment": {"MODE": "standalone", "NACOS_AUTH_ENABLE": "true", "NACOS_AUTH_ADMIN_ENABLE": "true",
                        "NACOS_AUTH_CONSOLE_ENABLE": "true", "NACOS_AUTH_CACHE_ENABLE": "false",
                        "NACOS_AUTH_TOKEN": secret("NACOS_AUTH_TOKEN"), "NACOS_AUTH_IDENTITY_KEY": "jddaIdentity",
                        "NACOS_AUTH_IDENTITY_VALUE": secret("NACOS_AUTH_IDENTITY_VALUE"),
                        "JVM_XMS": "64m", "JVM_XMX": "384m", "JVM_XMN": "64m"},
        "volumes": ["nacos-data:/home/nacos/data"],
    }
    stack = {"services": {"nacos": nacos}, "networks": {"runtime": {"internal": True}, "operator": {}},
             "volumes": {"nacos-data": {}}}
    upstreams, locations = [], []
    public = {}
    for module in modules:
        name = module["name"]
        package = module["package"]
        source = directory / name / "src/main"
        java = source / "java" / package.replace(".", "/")
        features = module["features"]
        dependencies = [dependency(*entry) for feature in features for entry in FEATURES[feature]]
        text(directory / name / "pom.xml", template("service-pom.xml.in", **values, service=name,
                                                    nacos=dependency(*NACOS), dependencies="\n".join(dependencies)))
        for item in sorted((TEMPLATES / "runtime").glob("*.in")):
            dest = java if item.name == "Application.java.in" else java / "runtime"
            text(dest / item.stem, template("runtime/" + item.name, package=package, service=name))
        for feature, adapter in (("sentinel", "SentinelTraffic"), ("redis", "RedisProjectCache"),
                                 ("rabbitmq", "RabbitProjectEvents"), ("openfeign", "FeignClients")):
            if feature in features:
                text(java / "runtime" / (adapter + ".java"),
                     template(f"optional/{adapter}.java.in", package=package, service=name))
        migrations = source / "resources/db/migration"
        if module["migration"]:
            text(migrations / "V1__project.sql", module["migration"] + "\n")
        if "rabbitmq" in features:
            text(migrations / "V9000__jdda_outbox.sql", (TEMPLATES / "optional/V9000__jdda_outbox.sql").read_text())
        container, app = service_settings(module, project["project"], stack)
        dump(source / "resources/application.yml", app)
        dump(directory / "ops/nacos" / (name + ".json"), module["policy"])
        stack["services"][name] = container
        public[name] = {key: module[key] for key in ("name", "package", "replicas", "features", "routes")}
        if project["ingress"] == "nginx" and module["routes"]:
            upstream = name
            upstreams.append(f"upstream {upstream} {{ zone {upstream} 64k; server {name}:8080 resolve; }}")
            for route in module["routes"]:
                locations.append(f"    location ~ ^{route}(/|$) {{ proxy_pass http://{upstream}; }}")
    if project["ingress"] == "nginx":
        stack["services"]["edge"] = {
            "image": IMAGES["nginx"], "networks": ["runtime", "operator"], "ports": ["127.0.0.1::8080"],
            "volumes": ["./ops/nginx.conf:/etc/nginx/conf.d/default.conf:ro"],
            "depends_on": {name: {"condition": "service_healthy"} for name, mod in public.items() if mod["routes"]},
        }
        text(directory / "ops/nginx.conf", "resolver 127.0.0.11 valid=2s ipv6=off;\n"
             + "\n".join(upstreams) + "\nserver {\n    listen 8080;\n    proxy_connect_timeout 1s;\n"
             + "    proxy_read_timeout 4s;\n    client_max_body_size 64k;\n"
             + "\n".join(locations) + "\n    location / { return 404; }\n}\n")
    if project["ingress"] == "gateway":
        add_gateway(directory, values, public, stack)
    if any("postgres" in module["features"] for module in modules):
        text(directory / "ops/postgres-init.sh", template("postgres-init.sh.in"))
    for service in stack["services"].values():
        service["platform"] = "linux/amd64"
    dump(directory / "compose.json", stack)
    dump(directory / "jdda-project.json", {**project, "modules": list(public.values()), "images": IMAGES})
    text(directory / "README.md", f"# {project['project']}\n\n"
         "Java 21 services with a Nacos policy per module and a digest-pinned Docker baseline.\n\n"
         "Use the project manifest and exact Git revision to build and deploy. "
         "The deployment operator supplies Nacos and infrastructure credentials.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--task-plan", type=Path)
    parser.add_argument("--plan-only", action="store_true", help="Print approved work scopes without rendering files.")
    parser.add_argument("--minimal", action="store_true", help="Keep PostgreSQL when selected; omit optional adapters and proxy.")
    args = parser.parse_args()
    try:
        project, tasks = normalize(json.loads(args.manifest.read_text(encoding="utf-8-sig")), args.minimal)
        plan = {"project": project["project"], "tasks": tasks}
        if args.plan_only:
            print(json.dumps(plan))
            return
        if args.output is None:
            raise ValueError("Set --output or --plan-only.")
        output = args.output.resolve()
        if output.exists():
            raise ValueError("Output must be a new directory.")
        output.parent.mkdir(parents=True, exist_ok=True)
        if args.task_plan and (args.task_plan.resolve().is_relative_to(output) or args.task_plan.exists()):
            raise ValueError("Use a new task plan path outside the generated Git tree.")
        with TemporaryDirectory(prefix=".jdda-", dir=output.parent) as pending:
            tree = Path(pending) / "baseline"
            tree.mkdir()
            render_tree(project, tree)
            tree.rename(output)
        if args.task_plan:
            dump(args.task_plan, plan)
    except (ValueError, OSError) as error:
        parser.exit(2, f"Generation failed: {error}\n")
    print(output)


if __name__ == "__main__":
    main()
