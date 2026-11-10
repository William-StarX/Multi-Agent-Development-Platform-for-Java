"""Initialize and operate Nacos policies for a generated JDDA project."""
import argparse
import getpass
import hashlib
import json
import os
from pathlib import Path
import runpy
import time
import urllib.error
import urllib.parse
import urllib.request


class RefuseRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


HTTP = urllib.request.build_opener(urllib.request.ProxyHandler({}), RefuseRedirect())


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def request(url, method="GET", value=None, headers=None, form=False, timeout=8):
    headers = dict(headers or {})
    payload = None
    if value is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        payload = (urllib.parse.urlencode(value) if form else json.dumps(value)).encode()
    call = urllib.request.Request(url, data=payload, headers=headers, method=method)
    try:
        result = HTTP.open(call, timeout=timeout)
    except urllib.error.HTTPError as error:
        result = error
    with result:
        raw = result.read()
        try:
            data = json.loads(raw) if raw else None
        except ValueError:
            data = None
        return result.status, data, result.headers


def eventually(operation, description, timeout=40):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            value = operation()
            if value:
                return value
        except OSError:
            pass
        time.sleep(.25)
    raise RuntimeError(description + " did not become ready.")


class ProjectNacos:
    def __init__(self, address, group):
        uri = urllib.parse.urlsplit(address)
        require(uri.hostname and uri.path in ("", "/") and not (uri.username or uri.password or uri.query or uri.fragment)
                and (uri.scheme == "https" or (uri.scheme == "http" and uri.hostname in ("127.0.0.1", "::1", "localhost"))),
                "Use a Nacos HTTPS origin or an HTTP loopback address.")
        self.api = address.rstrip("/") + "/nacos/v3/"
        self.group = group
        self.token = None

    def post(self, path, values, token=None):
        return request(self.api + path, "POST", values, {"accessToken": token} if token else {}, form=True)

    def login(self, user, password):
        status, data, _ = self.post("auth/user/login", {"username": user, "password": password})
        return data.get("accessToken") if status == 200 and data else None

    def config_key(self, service):
        return {"namespaceId": "public", "groupName": self.group, "dataId": service + ".json"}

    def publish(self, service, config):
        content = json.dumps(config, separators=(",", ":"))
        status, data, _ = self.post("admin/cs/config", {**self.config_key(service), "type": "json", "content": content},
                                    self.token)
        require(status == 200 and data and data.get("data") is True, "Nacos did not accept the configuration.")
        return hashlib.sha256(content.encode()).hexdigest()

    def read(self, service):
        status, data, _ = request(self.api + "admin/cs/config?" + urllib.parse.urlencode(self.config_key(service)),
                                  headers={"accessToken": self.token})
        require(status == 200 and data and data.get("code") == 0, "Nacos configuration read failed.")
        return data["data"]["content"]

    def initialize(self, admin_password, policies, passwords):
        eventually(lambda: request(self.api + "admin/core/state")[0] < 500, "Nacos API", timeout=100)
        status, data, _ = self.post("auth/user/admin", {"password": admin_password})
        require(status == 200 and data and data.get("code") == 0,
                "Nacos administrator bootstrap failed. Initialize only a fresh dedicated instance.")
        self.token = eventually(lambda: self.login("nacos", admin_password), "Nacos admin session")
        for name, config in policies.items():
            role = name + "-reader"
            password = passwords[name]
            for path, values in (
                    ("auth/user", {"username": name, "password": password}),
                    ("auth/role", {"username": name, "role": role}),
                    ("auth/permission", {"role": role, "action": "r",
                                         "resource": f"public:{self.group}:config/{name}.json"})):
                status, data, _ = self.post(path, values, self.token)
                require(status == 200 and data and data.get("code") == 0,
                        f"Nacos read-only identity provisioning failed for {name} at {path}.")
            self.publish(name, config)
            reader = eventually(lambda: self.login(name, password), "Nacos reader session")
            status, data, _ = self.post("admin/cs/config", {
                **self.config_key(name), "type": "json", "content": json.dumps(config),
            }, reader)
            require(status == 403 or (data and data.get("code") in (403, 10011)),
                    "A Nacos application reader unexpectedly has write access.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("initialize", "read", "publish"))
    parser.add_argument("--project-dir", required=True, type=Path)
    parser.add_argument("--address", required=True)
    parser.add_argument("--operator", default="nacos")
    parser.add_argument("--module")
    parser.add_argument("--config", type=Path)
    args = parser.parse_args()
    try:
        project = json.loads((args.project_dir / "jdda-project.json").read_text(encoding="utf-8"))
        modules = {module["name"]: module for module in project["modules"]}
        nacos = ProjectNacos(args.address, project["project"])
        if args.action == "initialize":
            policies, passwords = {}, {}
            for name in modules:
                variable = name.upper().replace("-", "_") + "_NACOS_PASSWORD"
                passwords[name] = os.environ.get(variable, "")
                require(len(passwords[name]) >= 16, f"Set {variable} to the corresponding Compose reader password.")
                policies[name] = json.loads((args.project_dir / "ops/nacos" / (name + ".json")).read_text(encoding="utf-8"))
            nacos.initialize(getpass.getpass("New Nacos administrator password: "), policies, passwords)
            print("Initialized Nacos service policies and read-only identities.")
        else:
            require(args.module in modules, "Choose a module from jdda-project.json.")
            nacos.token = nacos.login(args.operator, getpass.getpass("Nacos operator password: "))
            require(nacos.token, "Nacos operator authentication failed.")
            if args.action == "read":
                print(nacos.read(args.module))
            else:
                require(args.config, "Publish requires --config with a JSON policy file.")
                generator = runpy.run_path(str(Path(__file__).with_name("Generate-Project.py")))
                config = json.loads(args.config.read_text(encoding="utf-8-sig"))
                policy = generator["policy_settings"](config, modules[args.module]["features"])
                print(nacos.publish(args.module, policy))
    except (OSError, RuntimeError, ValueError) as error:
        parser.exit(1, f"Nacos operation failed: {error}\n")


if __name__ == "__main__":
    main()
