"use strict";

const byId = id => document.getElementById(id);
const project = byId("project");
const moreProjects = byId("more-projects");
const toggle = byId("toggle-dispatch");
const previous = byId("previous");
const next = byId("next");
const query = byId("query");

const states = {
    DRAFT: "草稿", WAITING_DEPENDENCIES: "等待依赖", READY: "待领取", LEASED: "已领取", RUNNING: "执行中",
    VERIFYING: "验收中", RETRY_WAIT: "等待重试", BLOCKED: "阻塞", CANCEL_REQUESTED: "正在取消",
    CANCELLED: "已取消", SUCCEEDED: "通过", FAILED: "失败", SUPERSEDED: "已替代"
};

let token;
let projectCursor;
let taskCursor;
let pages = ["0"];
let searchQuery = "";
let dispatch;
let dispatchVersion;
let busy = false;
let selectedTask;
let traceVersion;
let traceRequest = 0;
let polling = false;

function stateLabel(state) {
    return Object.hasOwn(states, state) ? states[state] : state;
}

async function api(path, options = {}) {
    const headers = new Headers(options.headers);
    if (token) headers.set("Authorization", "Bearer " + token);
    if (options.body) headers.set("Content-Type", "application/json");
    const response = await fetch(path, { ...options, headers, cache: "no-store" });
    if (!response.ok) {
        const detail = await response.json().catch(() => ({}));
        throw Object.assign(new Error(detail.message || "请求失败（HTTP " + response.status + "）"), {
            status: response.status, code: detail.code
        });
    }
    return { data: await response.json(), etag: response.headers.get("ETag") };
}

function updateButtons() {
    project.disabled = busy || !project.options.length;
    moreProjects.disabled = busy;
    byId("refresh").disabled = busy || !project.value;
    query.disabled = byId("search-button").disabled = busy || !project.value;
    toggle.disabled = busy || !dispatchVersion;
    previous.disabled = busy || pages.length < 2;
    next.disabled = busy || !taskCursor;
    byId("guidance-submit").disabled = busy;
}

function message(id, text) {
    const element = byId(id);
    element.textContent = text;
    element.hidden = !text;
}

async function perform(operation) {
    if (busy) return;
    busy = true;
    updateButtons();
    message("error", "");
    message("notice", "");
    try {
        await operation();
    } catch (error) {
        if (error.status === 401) {
            token = undefined;
            byId("workspace").hidden = true;
            byId("sign-in").hidden = false;
            message("error", "配对或会话已失效。请重新运行启动脚本。");
        } else {
            message("error", error.message || "无法连接本机控制服务。");
        }
    } finally {
        busy = false;
        updateButtons();
    }
}

function projectApi() {
    return "/api/v1/projects/" + encodeURIComponent(project.value);
}

function traceApi(task) {
    return "/_console/projects/" + encodeURIComponent(task.project) + "/tasks/" +
        encodeURIComponent(task.id) + "/revisions/" + task.revision;
}

function record(title, detail) {
    const item = document.createElement("li");
    const name = document.createElement("strong");
    name.textContent = title;
    item.append(name);
    if (detail) {
        const paragraph = document.createElement("p");
        paragraph.textContent = detail;
        item.append(paragraph);
    }
    return item;
}

function timeline(id, items, render) {
    byId(id).replaceChildren(...(items.length ? items.map(render) : [record("暂无记录")]));
}

function dateLabel(value) {
    return value ? new Date(value).toLocaleString("zh-CN") : "";
}

async function loadTrace(task) {
    const request = ++traceRequest;
    const { data } = await api(traceApi(task) + "/trace");
    if (selectedTask !== task || request !== traceRequest) return;
    traceVersion = data.guidance.version;
    byId("trace-title").textContent = task.id + " · 修订 " + task.revision;
    byId("trace-state").textContent = stateLabel(data.snapshot.state);
    byId("trace-goal").textContent = data.delegation?.goal || "暂无已记录的交接目标。";
    const roles = ["总控", "中级 Agent", "次中级 Agent"];
    timeline("trace-delegation", data.delegation?.steps ?? [], (step, index) =>
        record(roles[index] + " " + step.from + " → " + step.to,
            [step.decisionSummary, step.instruction].filter(Boolean).join("\n")));
    timeline("trace-attempts", data.attempts, attempt => record(
        [attempt.agentId, attempt.attemptId, {
            ALLOCATED: "已领取", RUNNING: "执行中", RESULT_SUBMITTED: "已提交结果", CANCELLED: "已取消",
            ACCEPTED: "验收通过", REJECTED: "验收拒绝", EXPIRED: "租约已过期"
        }[attempt.state] ?? attempt.state].filter(Boolean).join(" · "),
        [[dateLabel(attempt.createdAt), dateLabel(attempt.finishedAt)].filter(Boolean).join(" → "),
            attempt.outcome, attempt.commit, attempt.digest,
            Number.isInteger(attempt.guidanceVersion) ? "结果使用指令版本 v" + attempt.guidanceVersion : ""]
            .filter(Boolean).join("\n")));
    timeline("trace-releases", data.releases, release => record(
        [release.status, release.targetRef].filter(Boolean).join(" · "),
        [release.candidate, release.runId ? "CI 运行 " + release.runId : ""].filter(Boolean).join("\n")));
    timeline("trace-events", data.events, event => record(stateLabel(event.state), dateLabel(event.createdAt)));
    const guidance = data.guidance;
    if (guidance.items.length) {
        timeline("guidance-history", guidance.items, item =>
            record("v" + item.version + " · " + item.actor + " · " + dateLabel(item.createdAt), item.text));
    } else byId("guidance-history").replaceChildren();
    byId("guidance-empty").hidden = guidance.items.length > 0;
    byId("guidance-unavailable").hidden = guidance.available;
    byId("guidance-form").hidden = !guidance.available;
    byId("trace").hidden = false;
}

byId("guidance-form").addEventListener("submit", event => {
    event.preventDefault();
    perform(async () => {
        const text = byId("guidance-text").value.trim();
        if (!selectedTask || !text) return;
        message("guidance-status", "");
        try {
            const { data } = await api(traceApi(selectedTask) + "/guidance", {
                method: "POST", body: JSON.stringify({ text, expectedVersion: traceVersion }),
                headers: { "Idempotency-Key": crypto.randomUUID() }
            });
            byId("guidance-text").value = "";
            message("guidance-status", "已记录指令 v" + data.version + "。");
        } catch (error) {
            if (error.status !== 409) throw error;
            await loadTrace(selectedTask);
            throw new Error(error.code === "INSTRUCTION_UPDATED"
                ? "指令记录已更新，请核对最新指令后重试。" : "此任务当前无法接收新指令。");
        }
        await loadTrace(selectedTask);
    });
});

setInterval(async () => {
    if (!selectedTask || busy || polling || !token || document.hidden) return;
    polling = true;
    try {
        await loadTrace(selectedTask);
    } catch (error) {
        if (error.status === 401) await perform(() => { throw error; });
    } finally {
        polling = false;
    }
}, 4000);

async function loadProjects() {
    const parameters = new URLSearchParams({ after: projectCursor || "" });
    const { data } = await api("/_console/projects?" + parameters);
    for (const id of data.projects) project.add(new Option(id, id));
    projectCursor = data.nextCursor;
    moreProjects.hidden = !projectCursor;
}

async function loadDispatch() {
    dispatchVersion = undefined;
    const badge = byId("dispatch-state");
    badge.textContent = "读取中";
    delete badge.dataset.paused;
    const { data, etag } = await api(projectApi() + "/dispatch");
    dispatch = data;
    dispatchVersion = etag;
    badge.textContent = data.paused ? "派发已暂停" : "派发已开启";
    badge.dataset.paused = String(data.paused);
    toggle.textContent = data.paused ? "恢复派发" : "暂停派发";
}

async function loadTasks() {
    const parameters = new URLSearchParams({ q: searchQuery, after: pages.at(-1), limit: "20", maxBytes: "16384" });
    const { data } = await api(projectApi() + "/library/tasks?" + parameters);
    const rows = byId("tasks");
    rows.replaceChildren();
    for (const task of data.items) {
        const row = rows.insertRow();
        row.insertCell().textContent = task.taskId;
        row.insertCell().textContent = task.revision;
        row.insertCell().textContent = stateLabel(task.state);
        const details = document.createElement("button");
        details.type = "button";
        details.textContent = "查看轨迹";
        details.setAttribute("aria-label", "查看 " + task.taskId + " 的轨迹");
        details.addEventListener("click", () => perform(async () => {
            selectedTask = { project: project.value, id: task.taskId, revision: task.revision };
            byId("trace").hidden = true;
            byId("guidance-text").value = "";
            message("guidance-status", "");
            await loadTrace(selectedTask);
            byId("trace-title").focus();
        }));
        row.insertCell().append(details);
    }
    byId("empty").hidden = data.items.length > 0;
    byId("page").textContent = "第 " + pages.length + " 页";
    taskCursor = data.nextCursor;
}

async function loadProject() {
    pages = ["0"];
    taskCursor = undefined;
    byId("tasks").replaceChildren();
    byId("empty").hidden = true;
    await loadDispatch();
    await loadTasks();
}

project.addEventListener("change", () => perform(async () => {
    selectedTask = undefined;
    byId("trace").hidden = true;
    await loadProject();
}));
moreProjects.addEventListener("click", () => perform(loadProjects));
byId("refresh").addEventListener("click", () => perform(async () => {
    await loadProject();
    if (selectedTask) await loadTrace(selectedTask);
}));
byId("search").addEventListener("submit", event => {
    event.preventDefault();
    perform(async () => {
        searchQuery = query.value.trim();
        await loadProject();
    });
});
previous.addEventListener("click", () => perform(async () => {
    const cursor = pages.pop();
    try { await loadTasks(); } catch (error) { pages.push(cursor); throw error; }
}));
next.addEventListener("click", () => perform(async () => {
    pages.push(taskCursor);
    try { await loadTasks(); } catch (error) { pages.pop(); throw error; }
}));
toggle.addEventListener("click", () => perform(async () => {
    const paused = !dispatch.paused;
    try {
        await api(projectApi() + "/dispatch", {
            method: "PUT", body: JSON.stringify({ paused }),
            headers: { "If-Match": dispatchVersion, "Idempotency-Key": crypto.randomUUID() }
        });
    } catch (error) {
        if (error.status !== 409) throw error;
        await loadDispatch();
        throw new Error("派发状态已被其他操作更新。请核对后重试。");
    }
    await loadDispatch();
    message("notice", paused ? "已暂停新任务派发。已领取任务可以继续执行。" : "已恢复新任务派发。");
}));

const code = new URLSearchParams(location.hash.slice(1)).get("pair");
if (location.hash) history.replaceState(null, "", location.pathname);
if (code) perform(async () => {
    const { data } = await api("/_console/session", { method: "POST", body: JSON.stringify({ code }) });
    token = data.token;
    await loadProjects();
    byId("sign-in").hidden = true;
    byId("workspace").hidden = false;
    if (Array.from(project.options).some(option => option.value === "demo-java")) project.value = "demo-java";
    if (project.value) await loadProject();
    else message("notice", "当前没有项目。");
});
