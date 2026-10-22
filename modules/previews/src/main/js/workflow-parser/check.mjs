// Developer check, not part of the Gradle build (README.md): runs the built bundle in an empty JavaScript context,
// without Node or browser globals, and prints its JSON for the given workflow files (default: the repository's
// workflows and fixtures/). With --compare it also runs the parser's own async convertWorkflowTemplate and reports
// where its jobs, needs, step counts, events or error count differ from the bundle's synchronous conversion.
//   node check.mjs [--compare] [file.yml ...]
import { readFileSync, readdirSync } from "node:fs";
import { createRequire } from "node:module";
import { basename, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

const here = dirname(fileURLToPath(import.meta.url));
const bundle = join(here, "../../resources/dev/sluide/previews/vendor/workflow-parser/workflow-parser.min.js");
const args = process.argv.slice(2);
const compare = args.includes("--compare");
let files = args.filter(a => a !== "--compare");
if (files.length === 0) {
    const yml = dir => readdirSync(dir).filter(f => /\.ya?ml$/.test(f)).sort().map(f => join(dir, f));
    files = [...yml(join(here, "../../../../../../.github/workflows")), ...yml(join(here, "fixtures"))];
}

const sandbox = vm.createContext({});
vm.runInContext(readFileSync(bundle, "utf8"), sandbox, { filename: "workflow-parser.min.js" });
const parser = vm.runInContext("globalThis.sluideWorkflowParser", sandbox);

const official = compare ? await loadOfficial() : undefined;
let differences = 0;
for (const file of files) {
    const text = readFileSync(file, "utf8");
    const result = JSON.parse(parser.parse(basename(file), text));
    console.log(`=== ${file}\n${JSON.stringify(result, null, 2)}`);
    if (official) differences += compareWithOfficial(file, result, await official(basename(file), text));
}
if (compare) {
    console.log(differences === 0 ? "compare: no differences" : `compare: ${differences} difference(s)`);
    process.exitCode = differences === 0 ? 0 : 1;
}

// Bundles a tiny Node script around convertWorkflowTemplate in memory (the package imports JSON without import
// attributes, so Node cannot load it directly).
async function loadOfficial() {
    const esbuild = await import("esbuild");
    const contents = `
        import { convertWorkflowTemplate, parseWorkflow, NoOperationTraceWriter } from "@actions/workflow-parser";
        import { ErrorPolicy } from "@actions/workflow-parser/model/convert";
        module.exports = async (name, content) => {
            const { context, value } = parseWorkflow({ name, content }, new NoOperationTraceWriter());
            if (!value) return { jobs: [], events: [], errors: context.errors.getErrors().length };
            const options = { fetchReusableWorkflowDepth: 0, errorPolicy: ErrorPolicy.TryConversion };
            const t = await convertWorkflowTemplate(context, value, undefined, options);
            return {
                jobs: (t.jobs || []).map(j => ({
                    id: j.id.value, needs: (j.needs || []).map(n => n.value),
                    steps: (j.steps || []).filter(s => s.run || s.uses).length
                })),
                events: Object.keys(t.events || {}),
                errors: context.errors.getErrors().length
            };
        };`;
    const built = await esbuild.build({
        stdin: { contents, resolveDir: here }, bundle: true, platform: "node", format: "cjs", write: false,
        logLevel: "error"
    });
    const module = { exports: {} };
    const require = createRequire(import.meta.url);
    new Function("module", "exports", "require", built.outputFiles[0].text)(module, module.exports, require);
    return module.exports;
}

function compareWithOfficial(file, ours, theirs) {
    const a = JSON.stringify({
        jobs: ours.jobs.map(j => ({ id: j.id, needs: j.needs, steps: j.steps.length })),
        events: ours.events.map(e => e.name),
        errors: ours.errors.length
    });
    const b = JSON.stringify(theirs);
    if (a === b) return 0;
    console.log(`compare: ${file} differs\n  bundle:   ${a}\n  official: ${b}`);
    return 1;
}
