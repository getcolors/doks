# Configuration

`colors.yml` is a flat, kebab-case, non-secret map. The worked example is the
package's own `colors.yml`; the worked deployment is `doks-dev`.

| Key | Meaning | Example |
|---|---|---|
| `profile` | Deployment identity: names the work directory, both state keys, the cluster and the registry. Safe identifier (`[A-Za-z0-9][A-Za-z0-9_-]{0,62}`). Never overlay it. | `doks-dev` |
| `workdir` | Where generated output goes, relative to `colors.yml`. | `.colors` |
| `provider-compute` | Managed Kubernetes provider from the pinned library's recipes: `digitalocean` or `vultr`. | `digitalocean` |
| `provider-backend` | Remote state backend: `r2` or `s3` (the library also accepts `gcs`, `oci`). | `r2` |
| `r2-bucket`, `r2-endpoint` | R2 backend settings (with `provider-backend: r2`). | `doks-state`, `https://<account>.r2.cloudflarestorage.com` |
| `s3-bucket`, `s3-region` | S3 backend settings (with `provider-backend: s3`). | `doks-state`, `eu-west-1` |
| `digitalocean-region` | DOKS region slug. | `ams3` |
| `doks-version` | DOKS version slug, `x.y.z-do.n`; must be currently offered by the API (checked on a real create). | `1.36.3-do.2` |
| `digitalocean-node-size` | Droplet size of the worker pool. | `s-2vcpu-4gb` |
| `digitalocean-node-count` | Worker count, 1-1000. | `2` |
| `digitalocean-name` | Optional cluster display name; absent, blank or `REPLACE_ME` uses the profile. Never changes state keys. | `dev-cluster` |
| `vultr-region` | VKE region. | `ams` |
| `vultr-vke-version` | VKE version, `vX.Y.Z+n`; must be currently offered. | `v1.33.0+1` |
| `vultr-node-plan` | Plan of the node pool. | `vc2-2c-4gb` |
| `vultr-node-count` | Node count, 1-1000. | `2` |
| `vultr-name` | Optional cluster label override, as `digitalocean-name`. | `dev-cluster` |
| `digitalocean-registry-tier` | Optional. Present, the deployment owns a DigitalOcean container registry named after the profile (lowercased, other characters removed) on this subscription tier — `starter`, `basic` or `professional` — integrated with the cluster; absent, there is no registry and the `registry` verb refuses. A validation error unless `provider-compute` is `digitalocean`. | `basic` |
| `compute-prevent-destroy` | Mandatory committed `true`. Bound to `prevent_destroy` on the cluster and the registry. Lift for one authorized delete with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false`. | `true` |

Settings of the unselected provider are accepted and ignored.

## Credentials

| Variable | Needed by |
|---|---|
| `COLORS_PAR_DO_TOKEN` | `create`, `delete` on DigitalOcean; `registry`; `check` when a registry is configured |
| `COLORS_PAR_VULTR_API_KEY` | `create`, `delete` on Vultr |
| `COLORS_PAR_R2_ACCESS_KEY_ID`, `COLORS_PAR_R2_SECRET_ACCESS_KEY` | every verb that reads or writes state (`create`, `delete`, `check`, `kubeconfig`) with `provider-backend: r2`; S3 uses the ambient AWS credential chain |

`build` and `--dry-run` need none. Never put their values in tracked files.

## Generated output

Under `<workdir>/<profile>/`: `compute/managed-kubernetes/` (the library's
rendered documents, `build` only), `doks-registry/` (the registry stage),
`kubeconfig` (owner-only, written by `create`, `check` and `kubeconfig`),
and `registry/push/config.json` (owner-only, written by `registry`, valid one
hour). State keys are `<profile>/compute/managed-kubernetes.tfstate` and
`<profile>/registry.tfstate` in the configured bucket.
