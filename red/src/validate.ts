import { managed_errors, registry } from "colors-compute-red";
import { readFileSync } from "node:fs";
const managedProviders = JSON.parse(readFileSync(new URL("../resources/managed-providers.json", import.meta.resolve("colors-compute-red")), "utf8"));
export type Opts = Record<string, any>;
export const missing = (value: any) => value == null || typeof value === "string" && !value.trim();
export const registryEnabled = (opts: Opts) => !missing(opts["digitalocean-registry-tier"]);
export const registryName = (opts: Opts) => String(opts.profile ?? "").toLowerCase().replace(/[^a-z0-9-]/g, "");
export const computeRequest = (opts: Opts) => ({ legacy_state_keys: [`${opts.profile}/cluster.tfstate`] });
export const envErrors = (env: Opts) => env.COLORS_PAR_PROFILE ? ["COLORS_PAR_PROFILE is set; profile must come from colors.yml only"] : [];
export function stateErrors(opts: Opts): string[] {
  const errors = ["profile", "workdir", "provider-compute", "provider-backend", "compute-prevent-destroy"].filter(key => missing(opts[key])).map(key => `:${key} is required`);
  if (!missing(opts.profile) && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$/.test(String(opts.profile))) errors.push(":profile must be a safe identifier");
  const providers = Object.keys(managedProviders).sort();
  if (!providers.includes(opts["provider-compute"])) errors.push(":provider-compute must be one of " + providers.join(", "));
  if (typeof opts["compute-prevent-destroy"] !== "boolean") errors.push(":compute-prevent-destroy must be true or false");
  if (!errors.length) errors.push(...managed_errors(opts, computeRequest(opts)));
  if (registryEnabled(opts)) {
    if (opts["provider-compute"] !== "digitalocean") errors.push(":digitalocean-registry-tier requires :provider-compute digitalocean");
    if (!["starter", "basic", "professional"].includes(opts["digitalocean-registry-tier"])) errors.push(":digitalocean-registry-tier must be starter, basic, or professional");
    if (!/^[a-z0-9][a-z0-9-]{1,62}$/.test(registryName(opts))) errors.push("the profile-derived registry name is not a valid DigitalOcean registry name");
  }
  return errors;
}
export function secretErrors(opts: Opts, event: string): string[] {
  const entries = registry as Opts;
  const provider = entries.compute[opts["provider-compute"]]?.secrets ?? [];
  const backend = entries.backend[opts["provider-backend"]]?.secrets ?? [];
  const required: string[] = ["create", "delete"].includes(event) ? [...provider, ...backend] : event === "check" ? [...backend, ...(registryEnabled(opts) ? ["do-token"] : [])] : event === "kubeconfig" ? backend : event === "registry" ? ["do-token"] : [];
  return [...new Set(required)].filter(key => missing(opts[key])).map(key => "required credential is not set: COLORS_PAR_" + key.toUpperCase().replaceAll("-", "_"));
}
