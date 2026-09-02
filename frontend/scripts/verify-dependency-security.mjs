import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const workspace = readFileSync(new URL("../pnpm-workspace.yaml", import.meta.url), "utf8");
const lockfile = readFileSync(new URL("../pnpm-lock.yaml", import.meta.url), "utf8");
const patch = readFileSync(new URL("../patches/minimatch@3.1.5.patch", import.meta.url));
const expectedPatchHash = "5765164f0ee06343670e2867c4f615109123e8b710587cfe098abad80995efa7";

if (!workspace.includes("  brace-expansion: 5.0.9")) {
  throw new Error("brace-expansion must resolve globally to patched version 5.0.9");
}
if (!workspace.includes("  minimatch@3.1.5: patches/minimatch@3.1.5.patch")) {
  throw new Error("tracked minimatch CommonJS compatibility patch is not configured");
}

const patchHash = createHash("sha256").update(patch).digest("hex");
if (patchHash !== expectedPatchHash || !lockfile.includes(`minimatch@3.1.5: ${expectedPatchHash}`)) {
  throw new Error(`minimatch patch hash mismatch: ${patchHash}`);
}

const resolved = new Set(
  [...lockfile.matchAll(/^  brace-expansion@([^:]+):$/gm)].map((match) => match[1]),
);
if (resolved.size !== 1 || !resolved.has("5.0.9")) {
  throw new Error(`unsafe brace-expansion lock resolutions: ${[...resolved].join(", ") || "none"}`);
}

const require = createRequire(import.meta.url);
const eslintRequire = createRequire(require.resolve("eslint"));
const minimatchPath = eslintRequire.resolve("minimatch");
const dependencyRequire = createRequire(minimatchPath);
const braceExpansion = dependencyRequire("brace-expansion");
const minimatch = eslintRequire("minimatch");

if (typeof braceExpansion.expand !== "function" || typeof minimatch !== "function") {
  throw new Error("patched brace-expansion/minimatch CommonJS contract is unavailable");
}
if (!minimatch("a.js", "{a,b}.js") || minimatch.braceExpand("{a,b}.js").length !== 2) {
  throw new Error("minimatch 3 brace compatibility regression");
}

const maxLength = 4096;
const expanded = braceExpansion.expand("{a,b}".repeat(3000), { max: 100_000, maxLength });
const expandedLength = expanded.reduce((total, value) => total + value.length, 0);
if (expandedLength > maxLength) {
  throw new Error(`brace expansion exceeded maxLength: ${expandedLength}`);
}

console.log(`dependency security verified: brace-expansion 5.0.9, patch ${patchHash}, PoC ${expandedLength}/${maxLength}`);
