// Entry of the vendored workflow parser bundle (README.md: build, JSON contract).
// Exposes globalThis.sluideWorkflowParser.parse(fileName, text): a JSON string of schema 1. Never throws.
import {
    NoOperationTraceWriter, isBasicExpression, isMapping, isSequence, isString, parseWorkflow
} from "@actions/workflow-parser";
// convertWorkflowTemplate is async (it may fetch reusable workflows), so a WebEngine could only get its result through
// a callback. Without a file provider nothing in it awaits, so we run its converters synchronously, as it does.
import { convertConcurrency } from "@actions/workflow-parser/model/converter/concurrency";
import { convertOn } from "@actions/workflow-parser/model/converter/events";
import { convertJobs } from "@actions/workflow-parser/model/converter/jobs";
import { handleTemplateTokenErrors } from "@actions/workflow-parser/model/converter/handle-errors";

const SCHEMA = 1;
const NULL_TOKEN = 7; // TokenType.Null

function parse(fileName, text) {
    try {
        return JSON.stringify(convert(fileName == null ? "" : String(fileName), text == null ? "" : String(text)));
    } catch (e) {
        const errors = [failure("the workflow", e)];
        return JSON.stringify({ schema: SCHEMA, errors, name: null, events: [], jobs: [] });
    }
}

function convert(fileName, text) {
    const { context, value: root } = parseWorkflow({ name: fileName, content: text }, new NoOperationTraceWriter());
    let name, on, jobs, jobsToken;
    if (root) {
        // The loop and error handling of convertWorkflowTemplate with ErrorPolicy.TryConversion, which converts despite
        // schema errors, minus fetching reusable workflows; it also keeps the workflow name and the jobs token.
        handleTemplateTokenErrors(root, context, undefined, () => {
            for (const item of root.assertMapping("root")) {
                switch (item.key.assertString("root key").value) {
                    case "name":
                        name = item.value;
                        break;
                    case "on":
                        on = handleTemplateTokenErrors(root, context, {}, () => convertOn(context, item.value));
                        break;
                    case "jobs":
                        jobsToken = item.value;
                        jobs = handleTemplateTokenErrors(root, context, [], () => convertJobs(context, item.value));
                        break;
                    case "concurrency":
                        handleTemplateTokenErrors(root, context, {}, () => convertConcurrency(context, item.value));
                        break;
                }
            }
        });
    }
    // Each part is read on its own: a failure here loses only that part and is reported as an error.
    const ours = [];
    const out = { schema: SCHEMA, errors: [], name: null, events: [], jobs: [] };
    out.errors = guarded(ours, "the errors", [], () => context.errors.getErrors().map(error));
    out.name = guarded(ours, "the workflow name", null, () => text0(name));
    out.events = guarded(ours, "the events", [], () => events(on));
    const raw = guarded(ours, "the job positions", new Map(), () => rawMappings(jobsToken));
    out.jobs = (jobs || []).map(j => guarded(ours, "job " + jobId(j), null, () => job(j, raw))).filter(Boolean);
    out.errors.push(...ours);
    return out;
}

function guarded(failures, what, fallback, read) {
    try {
        return read();
    } catch (e) {
        failures.push(failure(what, e));
        return fallback;
    }
}

function failure(what, e) {
    return { message: "Could not read " + what + ": " + String((e && e.message) || e), line: null, column: null };
}

function jobId(j) {
    return j && j.id ? "'" + j.id.value + "'" : "";
}

// Positions are 1-based; YAML syntax errors and schema errors carry a range, a few converter errors only a prefix.
function error(e) {
    const start = e.range && e.range.start;
    const prefix = !start && /\(Line: (\d+), Col: (\d+)\)/.exec(e.prefix || "");
    return {
        message: e.rawMessage,
        line: start ? start.line : prefix ? Number(prefix[1]) : null,
        column: start ? start.column : prefix ? Number(prefix[2]) : null
    };
}

function events(config) {
    return Object.keys(config || {}).map(name => ({ name, detail: eventDetail(name, config[name]) }));
}

// "branches: main, release/**; paths-ignore: docs/**", "cron: 0 3 * * 1", "inputs: runner, dry-run" or null.
function eventDetail(name, config) {
    const parts = [];
    if (name === "schedule") {
        // A list of crons; an empty mapping for "on: schedule" or "schedule:" without entries (a schema error).
        const crons = Array.isArray(config) ? config : [];
        const cron = s => s.cron + (s.timezone ? " (" + s.timezone + ")" : "");
        if (crons.length) parts.push("cron: " + crons.map(cron).join(", "));
    } else {
        for (const [key, value] of Object.entries(config || {})) {
            if (Array.isArray(value)) parts.push(key + ": " + value.join(", "));
            else if (value && typeof value === "object" && Object.keys(value).length) {
                parts.push(key + ": " + Object.keys(value).join(", "));
            }
        }
    }
    return parts.length ? parts.join("; ") : null;
}

function job(j, raw) {
    const reusable = j.type === "reusableWorkflowJob";
    return {
        id: j.id.value,
        name: j.name === j.id ? null : text0(j.name), // the converter defaults the name to the id token
        line: line(j.id),
        type: j.type,
        needs: (j.needs || []).map(n => n.value),
        if: condition(j.if, raw.get(j.id)),
        runsOn: reusable ? null : text0(j["runs-on"]),
        matrix: matrix(j.strategy),
        uses: reusable ? j.ref.value : null,
        with: reusable && j["input-values"] ? texts(j["input-values"]) : null,
        secretsInherit: reusable && j["inherit-secrets"] === true,
        // Only run and uses steps; the experimental wait, cancel and parallel steps are left out.
        steps: reusable ? [] : (j.steps || []).filter(s => s.run || s.uses).map(s => step(s, raw))
    };
}

function step(s, raw) {
    const mapping = raw.get(s.uses || s.run);
    const run = s.run ? (text0(s.run) || "").split("\n").find(l => l.trim()) || "" : null;
    return {
        id: s.id && !s.id.startsWith("__") ? s.id : null, // the converter generates ids starting with "__"
        name: text0(s.name),
        line: line(mapping),
        uses: s.run ? null : s.uses.value,
        run: run === null ? null : run.trim(),
        if: condition(s.if, mapping)
    };
}

// The condition as written. The converter wraps it in "success() && (...)", keeps YAML quotes in its source, and
// injects a range-less success() when there is none (or when the written one is invalid; that is also an error).
function condition(token, mapping) {
    const written = mapping && find(mapping, "if");
    const value = written ? text0(written) : token && token.range ? token.source || token.expression : null;
    return value && value.trim() ? value.trim() : null;
}

// The converted jobs and steps lose their mappings: index the job mappings by their key token (the converted job's
// id) and the step mappings by each of their value tokens (the converted step's uses or run).
function rawMappings(jobs) {
    const raw = new Map();
    if (!jobs || !isMapping(jobs)) return raw;
    for (const { key, value: def } of jobs) {
        if (!isMapping(def)) continue;
        raw.set(key, def);
        const steps = find(def, "steps");
        if (!steps || !isSequence(steps)) continue;
        for (const entry of steps) {
            if (isMapping(entry)) for (const { value } of entry) raw.set(value, entry);
        }
    }
    return raw;
}

// null unless strategy.matrix is a mapping or an expression; anything else is a schema error.
function matrix(strategy) {
    let m;
    if (strategy && isExpression(strategy)) m = strategy;
    else if (strategy && isMapping(strategy)) m = find(strategy, "matrix");
    if (!m || !(isExpression(m) || isMapping(m))) return null;
    const out = {
        expression: null, axes: {}, include: [], exclude: [], includeExpression: null, excludeExpression: null
    };
    if (isExpression(m)) {
        out.expression = text0(m); // matrix: ${{ fromJSON(...) }} or the whole strategy as one expression
        return out;
    }
    for (const { key, value } of m) {
        const k = key.toString();
        if (k === "include" || k === "exclude") {
            if (isSequence(value)) out[k] = [...value].filter(isMapping).map(texts);
            else if (isExpression(value)) out[k + "Expression"] = text0(value);
        } else if (isSequence(value)) {
            out.axes[k] = [...value].map(text0);
        } else if (isExpression(value)) {
            out.axes[k] = [text0(value)];
        }
    }
    return out;
}

function isExpression(token) {
    return isBasicExpression(token) || token.isExpression === true;
}

function find(mapping, name) {
    for (const { key, value } of mapping) if (key.toString() === name) return value;
    return undefined;
}

function texts(mapping) {
    const out = {};
    for (const { key, value } of mapping) out[key.toString()] = text0(value);
    return out;
}

// Text of a token: strings unquoted, expressions as written, sequences and mappings in YAML flow style, YAML null as
// null. A value mixing text and expressions has its text as source (its expression is format(...)); a value that is
// one expression has the raw YAML, quotes included, as source, so it is rebuilt from the expression inside ${{ }}.
function text0(token) {
    if (!token || token.templateTokenType === NULL_TOKEN) return null;
    if (isBasicExpression(token)) return token.originalExpressions ? token.source : "${{ " + token.expression + " }}";
    if (isString(token)) return token.value;
    if (isSequence(token)) return "[" + [...token].map(text0).join(", ") + "]";
    if (isMapping(token)) return "{" + [...token].map(p => p.key.toString() + ": " + text0(p.value)).join(", ") + "}";
    return token.toString(); // boolean, number
}

function line(token) {
    return token && token.range ? token.range.start.line : null;
}

globalThis.sluideWorkflowParser = { schema: SCHEMA, parse };
