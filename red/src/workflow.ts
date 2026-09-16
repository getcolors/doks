import { dirname, isAbsolute, resolve } from "node:path";
import { readPars } from "red/cli";
import { preflight } from "red/lifecycle";
import * as dryRun from "red/dry-run";
import * as progress from "red/progress";
import { workflow, failed, type WireDecl, type Opts } from "red/workflow";
import * as tools from "./tools.ts";
import * as validate from "./validate.ts";
export const events = ["build", "create", "check", "kubeconfig", "registry", "delete"];
export const defaults = { "provider-compute": "digitalocean", "provider-backend": "r2", "compute-prevent-destroy": true, workdir: ".colors" };
export async function startStep(opts: Opts, env = process.env): Promise<Opts> {
  return preflight(opts, {
    defaults, overlay: readPars,
    validators: [
      (_o, e) => validate.envErrors(e),
      o => validate.stateErrors(o),
      (o, _e, c) => c.event === "registry" && !validate.registryEnabled(o) ? [":digitalocean-registry-tier is not set; the registry verb needs a deployment-owned registry"] : [],
      (o, _e, c) => c.real ? validate.secretErrors(o, c.event ?? "") : [],
      (o, _e, c) => c.event === "delete" && o["compute-prevent-destroy"] !== false ? ["compute destruction is protected; set COLORS_PAR_COMPUTE_PREVENT_DESTROY=false to delete"] : [],
    ],
    afterValidate: o => ({ ...o, workdir: !isAbsolute(String(o.workdir)) && o["red/state-file"] ? resolve(dirname(o["red/state-file"]), o.workdir) : o.workdir, "red/exit": 0 }),
  }, env);
}
export const steps: Record<string, (opts: Opts) => any> = { start: startStep, infrastructure: tools.infrastructureStep, load: tools.loadStep, registry: tools.registryStep, "registry-link": tools.registryLinkStep, "registry-unlink": tools.registryUnlinkStep, "check-nodes": tools.checkNodesStep, "check-registry": tools.checkRegistryStep, kubeconfig: tools.kubeconfigStep, "registry-credentials": tools.registryCredentialsStep, cleanup: tools.cleanupStep };
export const graphs: Record<string, string[]> = {
  build: ["start", "infrastructure", "registry", "registry-link"],
  create: ["start", "infrastructure", "registry", "registry-link"],
  delete: ["start", "load", "registry-unlink", "infrastructure", "registry", "cleanup"],
  check: ["start", "load", "check-nodes", "check-registry"],
  kubeconfig: ["start", "load", "kubeconfig"],
  registry: ["start", "registry-credentials"],
};
export function wireFn(step: string, opts: Opts): WireDecl | undefined {
  const graph = graphs[opts["red/event"]], name = step.replace(/^doks\//, ""), index = graph.indexOf(name);
  if (index === -1) return undefined;
  return [steps[name], ...(index + 1 < graph.length ? ["doks/" + graph[index + 1]] : [])];
}
export const doksWorkflow = dryRun.advise(progress.advise(workflow({ start: "doks/start", wireFn, nextFn: (_step, successors, opts) => failed(opts) ? [] : (successors ?? []).map(step => [step, opts]) })), Object.keys(steps).filter(s => s !== "start").map(s => "doks/" + s));
