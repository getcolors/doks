import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import * as tofu from "red/tofu";
import { contentSpec } from "red/scaffold";
import { runtime } from "red/runtime";
import { backend_plan, registry, plan_managed_kubernetes, managed_kubernetes, read_managed_kubernetes } from "colors-compute-red";
import { computeRequest, registryEnabled, registryName, type Opts } from "./validate.ts";
export const API_BASE = "https://api.digitalocean.com/v2";
export const REFUSED = "managed compute lifecycle refused; inspect state ownership, credentials and the journal";
export const profileDir = (opts: Opts) => join(opts.workdir, opts.profile);
export const kubeconfigPath = (opts: Opts) => join(profileDir(opts), "kubeconfig");
export const pushConfigPath = (opts: Opts) => join(profileDir(opts), "registry/push/config.json");
export function environment(opts: Opts): Record<string, string | undefined> {
  const env = { ...process.env };
  for (const [key, value] of Object.entries(opts)) if (!key.includes("/") && value != null) env["COLORS_PAR_" + key.toUpperCase().replaceAll("-", "_")] = String(value);
  delete env.COLORS_PAR_PROFILE;
  return env;
}
export function credentialEnv(opts: Opts): Record<string, string> {
  const mapping = { ...(registry as Opts).compute[opts["provider-compute"]]["tofu-env"] };
  const backend = opts["provider-backend"];
  if (["r2", "oci"].includes(backend)) Object.assign(mapping, { [`${backend}-access-key-id`]: "AWS_ACCESS_KEY_ID", [`${backend}-secret-access-key`]: "AWS_SECRET_ACCESS_KEY" });
  return Object.fromEntries(Object.entries(mapping).filter(([key]) => opts[key]).map(([key, name]) => [name, String(opts[key])])) as Record<string, string>;
}
export function computeResult(opts: Opts, result: Opts): Opts {
  if (["planned", "ready", "present"].includes(result.status)) return { ...opts, "red/exit": 0, "doks/cluster": result.params, ...(result.kubeconfig_path ? { "doks/kubeconfig-path": result.kubeconfig_path } : {}) };
  if (result.status === "destroyed") return { ...opts, "red/exit": 0, "doks/cluster-absent": true };
  return { ...opts, "red/exit": 1, "red/err": result.errors?.join("\n") || REFUSED };
}
function sorted(value: any): any {
  if (Array.isArray(value)) return value.map(sorted);
  if (value && typeof value === "object") return Object.fromEntries(Object.keys(value).sort().map(key => [key, sorted(value[key])]));
  return value;
}
export interface ComputeDeps {
  plan?: typeof plan_managed_kubernetes;
  converge?: typeof managed_kubernetes;
  read?: typeof read_managed_kubernetes;
}
export async function infrastructureStep(opts: Opts, deps: ComputeDeps = {}): Promise<Opts> {
  try {
    const event = opts["red/event"];
    if (event === "delete" && opts["doks/cluster-absent"]) { console.log("cluster already absent; nothing to destroy"); return { ...opts, "red/exit": 0 }; }
    let result;
    if (event === "build") {
      result = (deps.plan ?? plan_managed_kubernetes)(opts, computeRequest(opts));
      const directory = join(profileDir(opts), "compute/managed-kubernetes");
      mkdirSync(directory, { recursive: true });
      for (const [name, doc] of Object.entries(result.documents)) writeFileSync(join(directory, name), JSON.stringify(sorted(doc), null, 2) + "\n");
    } else result = await (deps.converge ?? managed_kubernetes)(opts, computeRequest(opts), environment(opts));
    const outcome = computeResult(opts, result);
    if (event === "delete" && !outcome["red/exit"]) console.log("cluster destroyed");
    return outcome;
  } catch (error) { return { ...opts, "red/exit": 1, "red/err": `${REFUSED}: ${error instanceof Error ? error.message : error}` }; }
}
export async function loadStep(opts: Opts, deps: ComputeDeps = {}): Promise<Opts> {
  try {
    const result = await (deps.read ?? read_managed_kubernetes)(opts, computeRequest(opts), environment(opts));
    if (result.status === "present") return computeResult(opts, result);
    if (["absent", "destroyed"].includes(result.status)) return opts["red/event"] === "delete" ? { ...opts, "red/exit": 0, "doks/cluster-absent": true } : { ...opts, "red/exit": 1, "red/err": "managed cluster is not present; run create first" };
    return { ...opts, "red/exit": 1, "red/err": "managed compute inspection refused; existing owned state is required" };
  } catch (error) { return { ...opts, "red/exit": 1, "red/err": `managed compute inspection refused: ${error instanceof Error ? error.message : error}` }; }
}
export function registryDocument(opts: Opts): Opts {
  return {
    terraform: { required_providers: { digitalocean: { source: "digitalocean/digitalocean", version: "2.51.0" } } },
    provider: { digitalocean: {} },
    resource: { digitalocean_container_registry: { registry: { name: registryName(opts), subscription_tier_slug: opts["digitalocean-registry-tier"], lifecycle: { prevent_destroy: opts["compute-prevent-destroy"] } } } },
    output: { params: { value: { kind: "container-registry", provider: "digitalocean", profile: opts.profile, ...Object.fromEntries(["name", "endpoint", "server_url"].map(key => [key, "${digitalocean_container_registry.registry." + key + "}"])) } } },
  };
}
export async function registryStep(opts: Opts): Promise<Opts> {
  if (!registryEnabled(opts)) { if (opts["red/event"] === "delete") console.log("no registry configured; nothing to destroy"); return { ...opts, "red/exit": 0 }; }
  const directory = join(profileDir(opts), "doks-registry");
  const [kind, config] = Object.entries(backend_plan(opts, opts.profile + "/registry.tfstate").config.terraform.backend)[0];
  opts = tofu.backendAdvice(() => directory, kind, config as Opts)(opts);
  const result = await tofu.tofuWithSpec(opts, [contentSpec(join(directory, "registry.tf.json"), tofu.constructsJson([registryDocument(opts)]) + "\n")], { dir: directory, env: credentialEnv(opts) });
  if (opts["red/event"] === "delete" && !result["red/exit"]) console.log(`registry ${registryName(opts)} destroyed`);
  if (result["tofu/outputs"]?.params) result["doks/registry"] = result["tofu/outputs"].params;
  return result;
}
export type API = (opts: Opts, method: string, path: string, body?: Opts) => Promise<Opts>;
export const api: API = async (opts, method, path, body) => {
  const response = await fetch(API_BASE + path, { method, headers: { Authorization: "Bearer " + opts["do-token"], Accept: "application/json", ...(body ? { "Content-Type": "application/json" } : {}) }, ...(body ? { body: JSON.stringify(body) } : {}), signal: AbortSignal.timeout(30000) });
  const raw = await response.text();
  let parsed = null;
  try { parsed = JSON.parse(raw); } catch {}
  return { status: response.status, body: parsed, raw };
};
export const ok = (response: Opts) => response.status >= 200 && response.status <= 299;
export const apiFailure = (what: string, response: Opts) => `${what} failed: HTTP ${response.status}` + (response.body?.message ? " " + response.body.message : "");
export async function registryIntegrated(opts: Opts, id: string, call: API = api): Promise<boolean> {
  const response = await call(opts, "GET", "/kubernetes/clusters/" + id);
  return ok(response) && response.body?.kubernetes_cluster?.registry_enabled === true;
}
export async function accountRegistries(opts: Opts, call: API = api): Promise<string[] | null> {
  const many = await call(opts, "GET", "/registries");
  if (ok(many)) return (many.body?.registries ?? []).map((r: Opts) => r.name);
  const one = await call(opts, "GET", "/registry");
  if (ok(one)) return [one.body?.registry?.name];
  return one.status === 404 ? [] : null;
}
export async function registryLinkStep(opts: Opts, call: API = api): Promise<Opts> {
  return !registryEnabled(opts) || opts["red/event"] !== "create" ? { ...opts, "red/exit": 0 } : integration(opts, false, call);
}
export async function registryUnlinkStep(opts: Opts, call: API = api): Promise<Opts> {
  if (!registryEnabled(opts) || opts["doks/cluster-absent"]) { console.log("no registry integration to remove"); return { ...opts, "red/exit": 0 }; }
  return integration(opts, true, call);
}
async function integration(opts: Opts, remove: boolean, call: API): Promise<Opts> {
  try {
    const id = opts["doks/cluster"]?.cluster_id;
    if (!id) throw new Error(remove ? "cluster id unavailable from state" : "cluster id unavailable after converge");
    const response = await call(opts, remove ? "DELETE" : "POST", "/kubernetes/registry", { cluster_uuids: [id] });
    if (!(ok(response) || remove && response.status === 404)) throw new Error(apiFailure(remove ? "registry integration removal" : "registry integration", response));
    if (!remove && !await registryIntegrated(opts, id, call)) throw new Error("the cluster does not report the registry integration");
    console.log(remove ? `registry integration removed from cluster ${id}` : `registry ${registryName(opts)} integrated with cluster ${id}`);
    return { ...opts, "red/exit": 0 };
  } catch (error) { return { ...opts, "red/exit": 1, "red/err": error instanceof Error ? error.message : String(error) }; }
}
export function writePrivate(path: string, content: string): string {
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
  chmodSync(dirname(path), 0o700);
  const temporary = mkdtempSync(join(dirname(path), ".credential-"));
  try {
    writeFileSync(join(temporary, "config"), content, { mode: 0o600 });
    renameSync(join(temporary, "config"), path);
  } finally { rmSync(temporary, { recursive: true, force: true }); }
  return path;
}
export async function registryCredentialsStep(opts: Opts, call: API = api): Promise<Opts> {
  try {
    const response = await call(opts, "GET", "/registry/docker-credentials?read_write=true&expiry_seconds=3600");
    if (!ok(response) || !response.body?.auths || typeof response.body.auths !== "object" || Array.isArray(response.body.auths)) throw new Error(apiFailure("registry credential request", response));
    const path = writePrivate(pushConfigPath(opts), response.raw);
    console.log(`registry registry.digitalocean.com/${registryName(opts)}`);
    console.log(`docker config (read-write, 1 hour): ${path}`);
    return { ...opts, "red/exit": 0, "doks/push-config-path": path };
  } catch (error) { return { ...opts, "red/exit": 1, "red/err": error instanceof Error ? error.message : String(error) }; }
}
export function nodeReport(document: Opts): { nodes: Opts[]; errors: string[] } {
  const nodes: Opts[] = (document.items ?? []).map((node: Opts) => ({ name: node.metadata?.name, ip: node.status?.addresses?.find((a: Opts) => a.type === "ExternalIP")?.address ?? null, "ready?": !!node.status?.conditions?.some((c: Opts) => c.type === "Ready" && c.status === "True") }));
  return { nodes, errors: nodes.length ? nodes.filter(n => !n["ready?"]).map(n => `node ${n.name} is not Ready`) : ["the cluster reports no nodes"] };
}
export async function checkNodesStep(opts: Opts, execute: typeof runtime.exec = runtime.exec): Promise<Opts> {
  const result = await execute(["kubectl", "--kubeconfig", opts["doks/kubeconfig-path"] || kubeconfigPath(opts), "get", "nodes", "-o", "json"]);
  if (result.exit) return { ...opts, "red/exit": 1, "red/err": "kubectl get nodes failed: " + (result.err || result.out || "(no output)") };
  let doc = {};
  try { doc = JSON.parse(result.out); } catch {}
  const report = nodeReport(doc);
  console.log(`cluster ${opts["doks/cluster"]?.name} ${opts["doks/cluster"]?.cluster_id}`);
  for (const n of report.nodes) console.log(`node ${n.name} ${n.ip || "-"} ${n["ready?"] ? "Ready" : "NotReady"}`);
  return report.errors.length ? { ...opts, "red/exit": 1, "red/err": report.errors.join("\n") } : { ...opts, "red/exit": 0, "doks/nodes": report.nodes };
}
export async function checkRegistryStep(opts: Opts, call: API = api): Promise<Opts> {
  if (!registryEnabled(opts)) return { ...opts, "red/exit": 0 };
  const names = await accountRegistries(opts, call), name = registryName(opts), id = opts["doks/cluster"].cluster_id;
  const error = names === null ? "the DigitalOcean registry API gave no answer" : !names.includes(name) ? `registry ${name} does not exist in this account` : !await registryIntegrated(opts, id, call) ? `registry ${name} is not integrated with cluster ${id}` : null;
  if (error) return { ...opts, "red/exit": 1, "red/err": error };
  console.log(`registry registry.digitalocean.com/${name} integrated with cluster ${id}`);
  return { ...opts, "red/exit": 0 };
}
export function kubeconfigStep(opts: Opts): Opts {
  const path = opts["doks/kubeconfig-path"] || kubeconfigPath(opts);
  if (!existsSync(path)) return { ...opts, "red/exit": 1, "red/err": `no kubeconfig was materialized at ${path}` };
  chmodSync(path, 0o600); console.log("kubeconfig: " + path);
  return { ...opts, "red/exit": 0, "doks/kubeconfig-path": path };
}
export function cleanupStep(opts: Opts): Opts {
  try {
    rmSync(kubeconfigPath(opts), { force: true }); rmSync(pushConfigPath(opts), { force: true });
    const directory = join(profileDir(opts), "registry");
    try { rmSync(directory, { recursive: true, force: true }); } catch {}
    const leftovers = existsSync(directory) ? [directory, ...readdirSync(directory, { recursive: true }).map(p => join(directory, String(p)))] : [];
    for (const path of leftovers) console.log(`cleanup: could not remove ${path} (owned by another user?); remove it by hand`);
    console.log(`cleanup done: removed ${kubeconfigPath(opts)} and ${directory}` + (leftovers.length ? " (with leftovers)" : ""));
    console.log(`delete complete for ${opts.profile}; the provider removes worker machines and cluster firewalls asynchronously over the next minutes, and check is expected to fail from now on`);
    return { ...opts, "red/exit": 0, "doks/cleanup-leftovers": leftovers };
  } catch (error) { return { ...opts, "red/exit": 1, "red/err": `cleanup failed after destruction: ${error instanceof Error ? error.message : error}` }; }
}
