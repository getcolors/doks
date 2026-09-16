import { test, expect } from "bun:test";
import { mkdtempSync, readFileSync, readdirSync, statSync, writeFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { startStep, graphs } from "../src/workflow.ts";
import * as tools from "../src/tools.ts";
import * as validate from "../src/validate.ts";
import { run } from "../src/cli.ts";
const root = resolve(import.meta.dir, "../..");
function fixture(provider = "digitalocean"): Record<string, any> {
  const directory = mkdtempSync(join(tmpdir(), "doks-test-"));
  return { ...(Bun.YAML.parse(readFileSync(join(root, `test/fixtures/${provider}.yml`), "utf8").replaceAll("WORKDIR", directory)) as Record<string, any>), "red/event": "build" };
}
for (const provider of ["digitalocean", "vultr"]) test(`${provider} render matches Green bytes`, async () => {
  const opts = fixture(provider);
  let result = await startStep(opts, {}); expect(result["red/exit"]).toBe(0);
  result = await tools.infrastructureStep(result); expect(result["red/exit"]).toBe(0);
  result = await tools.registryStep(result); expect(result["red/exit"]).toBe(0);
  const golden = join(root, "test/resources/golden", provider === "digitalocean" ? "digitalocean-registry" : "vultr");
  const files = (dir: string) => readdirSync(dir, { recursive: true }).map(String).filter(p => statSync(join(dir, p)).isFile()).sort();
  expect(files(opts.workdir)).toEqual(files(golden));
  for (const file of files(golden)) expect(readFileSync(join(opts.workdir, file), "utf8")).toBe(readFileSync(join(golden, file), "utf8"));
});
test("profile, deletion and registry guards run before effects", async () => {
  const opts = fixture();
  expect((await startStep(opts, { COLORS_PAR_PROFILE: "other" }))["red/exit"]).toBe(2);
  for (const dry of [true, false]) {
    const result = await startStep({ ...opts, "red/event": "delete", "red/dry-run": dry }, {});
    expect(result["red/exit"]).toBe(2); expect(result["red/err"]).toContain("protected");
  }
  expect((await startStep({ ...opts, "red/event": "create", "red/dry-run": true }, {}))["red/exit"]).toBe(0);
  expect((await startStep({ ...opts, "red/event": "create" }, {}))["red/exit"]).toBe(2);
  expect((await startStep({ ...fixture("vultr"), "red/event": "registry", "red/dry-run": true }, {}))["red/exit"]).toBe(2);
  expect(validate.stateErrors({ ...opts, profile: "../escape", "compute-prevent-destroy": "false" }).length).toBeGreaterThan(1);
  expect(validate.registryName({ profile: "Hello_World" })).toBe("helloworld");
});
test("absent clusters block reads but permit registry deletion", async () => {
  const opts = fixture();
  const deps = { read: async (_o: any, request: any) => { expect(request.legacy_state_keys).toEqual(["doks-fixture/cluster.tfstate"]); return { status: "absent" }; } };
  expect((await tools.loadStep({ ...opts, "red/event": "check" }, deps))["red/exit"]).toBe(1);
  const absent = await tools.loadStep({ ...opts, "red/event": "delete" }, deps);
  expect(absent["doks/cluster-absent"]).toBe(true);
  expect((await tools.infrastructureStep(absent, { converge: async () => { throw new Error("must not run"); } }))["red/exit"]).toBe(0);
  expect(tools.computeResult(opts, { status: "error", errors: ["ownership refused"] })["red/err"]).toBe("ownership refused");
});
test("registry integration verifies the cluster and handles unlink 404", async () => {
  const opts = { ...fixture(), "red/event": "create", "doks/cluster": { cluster_id: "cluster-id" } };
  const calls: any[] = [];
  const call: tools.API = async (_o, method, path, body) => { calls.push([method, path, body]); return method === "POST" ? { status: 204 } : { status: 200, body: { kubernetes_cluster: { registry_enabled: true } } }; };
  expect((await tools.registryLinkStep(opts, call))["red/exit"]).toBe(0);
  expect(calls[0]).toEqual(["POST", "/kubernetes/registry", { cluster_uuids: ["cluster-id"] }]);
  expect((await tools.registryUnlinkStep({ ...opts, "red/event": "delete" }, async () => ({ status: 404 })))["red/exit"]).toBe(0);
  expect((await tools.registryLinkStep(opts, async () => ({ status: 403, body: { message: "denied" } })))["red/exit"]).toBe(1);
  expect(await tools.accountRegistries(opts, async (_o, _m, path) => path === "/registries" ? { status: 404 } : { status: 200, body: { registry: { name: "example" } } })).toEqual(["example"]);
});
test("credentials are private and cleanup is idempotent", async () => {
  const opts = fixture();
  const raw = '{"auths":{"registry.digitalocean.com":{"auth":"SECRET"}}}';
  const result = await tools.registryCredentialsStep(opts, async () => ({ status: 200, body: JSON.parse(raw), raw }));
  expect(result["red/exit"]).toBe(0);
  const path = result["doks/push-config-path"];
  expect(statSync(path).mode & 0o777).toBe(0o600);
  expect(statSync(join(path, "..")).mode & 0o777).toBe(0o700);
  expect(readFileSync(path, "utf8")).toBe(raw);
  writeFileSync(tools.kubeconfigPath(opts), "credential");
  expect(tools.kubeconfigStep(opts)["red/exit"]).toBe(0);
  expect(statSync(tools.kubeconfigPath(opts)).mode & 0o777).toBe(0o600);
  for (let i = 0; i < 2; i++) expect(tools.cleanupStep({ ...opts, "red/event": "delete" })["red/exit"]).toBe(0);
  expect(existsSync(path)).toBe(false); expect(existsSync(tools.kubeconfigPath(opts))).toBe(false);
});
test("node readiness requires Ready=True and delete keeps cleanup last", () => {
  expect(tools.nodeReport({}).errors).toEqual(["the cluster reports no nodes"]);
  const node = { metadata: { name: "worker" }, status: { conditions: [{ type: "Ready", status: "True" }], addresses: [{ type: "ExternalIP", address: "192.0.2.1" }] } };
  expect(tools.nodeReport({ items: [node] }).errors).toEqual([]);
  node.status.conditions[0].status = "False";
  expect(tools.nodeReport({ items: [node] }).errors).toEqual(["node worker is not Ready"]);
  expect(graphs.delete).toEqual(["start", "load", "registry-unlink", "infrastructure", "registry", "cleanup"]);
});
test("CLI dry runs never render", async () => {
  const opts = fixture(), path = join(opts.workdir, "colors.yml"), workdir = join(opts.workdir, "rendered");
  writeFileSync(path, readFileSync(join(root, "test/fixtures/digitalocean.yml"), "utf8").replaceAll("WORKDIR", workdir));
  for (const event of ["create", "check", "kubeconfig", "registry"]) {
    expect((await run(event, "--dry-run", "-f", path))["red/exit"]).toBe(0);
    expect(existsSync(workdir)).toBe(false);
  }
  expect((await run("delete", "--dry-run", "-f", path))["red/exit"]).toBe(2);
  expect((await run("unknown"))["red/exit"]).toBe(2);
  expect((await run("help"))["red/exit"]).toBe(0);
});

test("native workflow stops at a failed delete stage", async () => {
  const { steps, doksWorkflow } = await import("../src/workflow.ts");
  const { run: runWorkflow } = await import("red/workflow");
  const original = { ...steps };
  try {
    for (const failure of ["load", "registry-unlink", "infrastructure", "registry"]) {
      const seen: string[] = [];
      for (const name of Object.keys(steps)) steps[name] = async opts => {
        seen.push(name);
        return { ...opts, "red/exit": name === failure ? 1 : 0, ...(name === failure ? { "red/err": "injected failure" } : {}) };
      };
      const result = await runWorkflow(doksWorkflow, { ...fixture(), "red/event": "delete", "compute-prevent-destroy": false });
      expect(result["red/exit"]).toBe(1);
      expect(seen).toEqual(graphs.delete.slice(0, graphs.delete.indexOf(failure) + 1));
      expect(seen).not.toContain("cleanup");
    }
  } finally { Object.assign(steps, original); }
});

test("node checks propagate kubectl errors and reject malformed output", async () => {
  const opts = fixture();
  expect((await tools.checkNodesStep(opts, async () => ({ exit: 1, out: "", err: "connection refused" })))["red/err"]).toContain("connection refused");
  expect((await tools.checkNodesStep(opts, async () => ({ exit: 0, out: "not-json", err: "" })))["red/exit"]).toBe(1);
});
