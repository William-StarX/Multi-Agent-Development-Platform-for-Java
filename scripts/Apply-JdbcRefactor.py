"""Apply a scoped mapper plan without sending local source or SQL to the model."""

import json
import re
import sys
from pathlib import Path


JAVA_ROOT = "/src/main/java/"
TOKEN = re.compile(r'''"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|/\*[\s\S]*?\*/|//[^\n]*|[()]''')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def replace_once(text, old, new):
    require(text.count(old) == 1, f"Expected one Java anchor: {old[:100]}")
    return text.replace(old, new, 1)


def package(path):
    return path.split(JAVA_ROOT, 1)[1].rsplit("/", 1)[0].replace("/", ".")


def call_end(text, start):
    depth = 1
    for token in TOKEN.finditer(text, start):
        value = token.group()
        if value == "(":
            depth += 1
        elif value == ")":
            depth -= 1
            if depth == 0:
                return token.end()
    raise ValueError("Unclosed JDBC invocation")


def extract(group, source, names):
    mapper_path = group["mapper"]
    mapper = Path(mapper_path).stem
    receiver = group.get("receiver", "jdbc")
    receiver_pattern = re.escape(receiver)
    transfers = []
    for spec in group.get("transfer", []):
        start = source.index(spec["start"])
        end = source.index(spec["end"], start + 1) if spec.get("end") else source.rindex("}")
        block = source[start:end].rstrip()
        for old, new in spec.get("edits", []):
            block = replace_once(block, old, new)
        transfers.append(block)
        source = source[:start] + source[end:]

    for old, new in group.get("edits", []):
        source = replace_once(source, old, new)

    pattern = re.compile(rf"\b{receiver_pattern}\.(?:query|queryForObject|queryForList|update)\(")
    matches = list(pattern.finditer(source))
    require(len(matches) == len(group["operations"]), f"JDBC call count changed in {group['source']}")
    methods = []
    replacements = []
    for match, op in zip(matches, group["operations"], strict=True):
        end = call_end(source, match.end())
        call = source[match.start():end]
        call = "jdbc" + call[len(receiver):]
        for old, new in op.get("edits", []):
            require(old in call, f"Expected mapper argument: {old}")
            call = call.replace(old, new)
        method = names[op["id"]]
        require(re.fullmatch(r"[a-z][A-Za-z0-9]{0,60}", method), "Invalid mapper method name")
        return_type = op["returns"]
        signature = f"    public {return_type} {method}({op.get('parameters', '')}) {{"
        tail = op.get("finish")
        prefix = "" if return_type == "void" or tail else "return "
        body = ["        " + line for line in op.get("setup", [])]
        body.append("        " + prefix + call + ";")
        if tail:
            body.append("        " + tail)
        methods.append("\n".join([signature, *body, "    }"]))
        before = op.get("before", "")
        start = match.start() - len(before)
        require(source[start:match.start()] == before, f"JDBC call prefix changed for {op['id']}")
        replacement = f"{op.get('prefix', '')}mapper.{method}({op.get('arguments', '')})"
        replacements.append((start, end, replacement))
    for start, end, replacement in reversed(replacements):
        source = source[:start] + replacement + source[end:]

    source = replace_once(source, "import org.springframework.jdbc.core.JdbcTemplate;\n",
                          f"import {package(mapper_path)}.{mapper};\n")
    source = re.sub(rf"\bJdbcTemplate {receiver_pattern}\b", f"{mapper} mapper", source)
    source = source.replace(f"this.{receiver} = {receiver};", "this.mapper = mapper;")
    for imported in group.get("removeImports", []):
        source = replace_once(source, f"import {imported};\n", "")
    for old, new in group.get("post", []):
        source = source.replace(old, new)
    require(not pattern.search(source) and "JdbcTemplate" not in source, f"Direct SQL remains in {group['source']}")

    imports = sorted({*group.get("imports", []), "org.springframework.jdbc.core.JdbcTemplate",
                      "org.springframework.stereotype.Repository"})
    header = "\n".join([f"package {package(mapper_path)};", "", *(f"import {item};" for item in imports),
                        "", "@Repository", f"public class {mapper} {{", "    private final JdbcTemplate jdbc;", "",
                        f"    public {mapper}(JdbcTemplate jdbc) {{", "        this.jdbc = jdbc;", "    }"])
    body = "\n\n".join([header, *methods, *transfers, *group.get("members", [])]) + "\n}\n"
    return source, body


def apply(root, spec, names):
    moves = spec["move"]
    writes = set(spec["write"])
    scope = writes | moves.keys() | set(moves.values())
    require(not spec["remove"], "Layer extraction only moves existing source")

    def local(relative):
        require(relative in scope, f"Outside the approved task: {relative}")
        path = root / relative
        require(path.resolve().is_relative_to(root), "Source path escapes workspace")
        return path

    config = spec["jdbcRefactor"]
    source = {path: local(path).read_text(encoding="utf-8") for path in moves}
    for path in writes:
        if local(path).is_file():
            source[path] = local(path).read_text(encoding="utf-8")
    for group in config["groups"]:
        path, mapper = group["source"], group["mapper"]
        require(mapper in writes and mapper not in source, "Expected a new approved mapper file")
        source[path], source[mapper] = extract(group, source[path], names)

    for relative, text in source.items():
        destination = moves.get(relative, relative)
        if destination != relative:
            text = re.sub(r"^package [\w.]+;", f"package {package(destination)};", text, count=1)
        for old, new in config.get("qualifiedRenames", {}).items():
            text = text.replace(old, new)
        for old, new in config.get("classRenames", {}).items():
            text = re.sub(rf"\b{re.escape(old)}\b", new, text)
        path = local(destination)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="\n")
    for path in moves:
        local(path).unlink()


if __name__ == "__main__":
    workspace, specification, report = map(Path, sys.argv[1:])
    spec = json.loads(specification.read_text(encoding="utf-8-sig"))
    result = json.loads(report.read_text(encoding="utf-8-sig"))
    apply(workspace.resolve(), spec, result["mapperMethods"])
