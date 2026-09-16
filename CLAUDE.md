# CLAUDE.md

## What this is

`doks` is a Green-only Package Skill for one managed Kubernetes cluster —
DigitalOcean Kubernetes (DOKS) or Vultr Kubernetes Engine (VKE) — provisioned
through the pinned `colors-compute` library's `managed-kubernetes` kind, plus
an optional deployment-owned DigitalOcean container registry integrated with
the cluster. It creates no machines: the Compute Provider Standard's machine
API, the cluster standard and the SSH standards do not apply. The first
consumer is `../doks-dev`; the cluster it materializes is what
`redis-operator-doks` and similar deployments run on.

The repository ships `package-doks-green` and its `green` launcher payload.
The root `./green` is a symlink to that payload. The launcher holds no logic:
validation, the graph and every step live under
`io.github.getcolors.doks.*`, where `bb test` reaches them.

## Commands

```sh
bb test                      # unit tests
bb golden                    # render both fixtures, diff against test/resources/golden
bb golden:accept             # regenerate after an intended change — read the diff first
./scripts/launcher.sh        # the copied-out payload builds on its own
./green build                # render .colors/<profile>/ — no provider calls, no credentials
./green create --dry-run     # walk the graph, skip every side effect
./green create               # only with explicit authorization
./green check                # every node Ready; registry present and integrated
./green kubeconfig           # re-materialize .colors/<profile>/kubeconfig from state
./green registry             # short-lived docker push config under .colors/<profile>/registry/push/
./green delete               # guarded and destructive
bb doks <verb> ...           # the same launcher through babashka
bb pin                       # stamp the payload with the pushed HEAD — never by hand
```

Never run a real `create` or `delete` without explicit authorization. Never
read or edit `.colors/`; it is generated and holds the kubeconfig and the
registry push credential. Never read `.envrc.private`.

## Desired state and credentials

`colors.yml` is a flat, non-secret map: `profile`, `workdir`,
`provider-compute` (`digitalocean` or `vultr`), `provider-backend` (`r2` or
`s3`; the library also accepts `gcs` and `oci`), the backend settings, the
selected provider's recipe keys (`digitalocean-region`, `doks-version`,
`digitalocean-node-size`, `digitalocean-node-count`; `vultr-region`,
`vultr-vke-version`, `vultr-node-plan`, `vultr-node-count`), the optional
`digitalocean-name` display override, the optional
`digitalocean-registry-tier`, and `compute-prevent-destroy: true`. See
`skills/package-doks-green/references/configuration.md`.

The cluster is named after the profile (Compute Name Standard). Provider
settings, version slugs and node counts are validated by the library from its
managed-provider recipes; the package validates only what it adds. Every
validation and usage failure exits 2 and lists every problem.

Credentials are `COLORS_PAR_DO_TOKEN` or `COLORS_PAR_VULTR_API_KEY`, and
`COLORS_PAR_R2_ACCESS_KEY_ID` / `COLORS_PAR_R2_SECRET_ACCESS_KEY` for R2 (S3
uses the ambient AWS chain). `build` and `--dry-run` need none. Never export
`COLORS_PAR_PROFILE`; the package refuses it because the profile keys remote
state. Keep `compute-prevent-destroy: true` committed and lift it only for one
authorized delete with `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` — the guard
refuses `delete --dry-run` too, since rehearsing a refused delete rehearses
nothing.

## Architecture

The workflow is one `wire-fn` per verb over these steps:

| Verb | Steps |
|---|---|
| `build`, `create` | start → infrastructure → registry → registry-link |
| `check` | start → load → check-nodes → check-registry |
| `kubeconfig` | start → load → kubeconfig |
| `registry` | start → registry-credentials |
| `delete` | start → load → registry-unlink → infrastructure → registry → cleanup |

- **infrastructure** calls `plan-managed-kubernetes` on `build` (writing the
  library's documents under `.colors/<profile>/compute/managed-kubernetes/`)
  and `managed-kubernetes` on a real `create`/`delete`. The library owns the
  journal, coordination, plan safety, the state key
  `<profile>/compute/managed-kubernetes.tfstate`, and the kubeconfig write.
  The request declares the pre-library key `<profile>/cluster.tfstate` as
  legacy so a create refuses while old state still owns a cluster.
- **load** calls `read-managed-kubernetes`: no mutation, kubeconfig
  re-materialized. An absent or destroyed cluster fails `check` and
  `kubeconfig`, and lets `delete` carry on to the registry stage.
- **registry** is a package-owned OpenTofu stage rendered as
  `.colors/<profile>/doks-registry/registry.tf.json`, run through
  `green.tofu/tofu-with-spec`, with state key `<profile>/registry.tfstate`
  on the same backend (the backend block comes from the library's
  `backend-plan`, so it carries the lockfile and checksum settings the
  cluster state uses). The one resource is `digitalocean_container_registry`
  named after the profile with `prevent_destroy` bound to
  `compute-prevent-destroy`. No region is set: a DOKS region is not
  necessarily a registry region. Failed tofu output reaches `:green/err`.
- **registry-link** / **registry-unlink** call `POST` / `DELETE
  /v2/kubernetes/registry` with the cluster UUID. Linked, DOKS injects an
  image-pull Secret named after the registry into every namespace, so
  workloads pull from `registry.digitalocean.com/<profile>/...` without any
  package-side credential rotation. The link is verified through the
  cluster's `registry_enabled` flag.
- **check-nodes** runs `kubectl --kubeconfig <path> get nodes -o json` and
  requires every node Ready, printing cluster name, id and each node's name
  and external IP (other packages' SSH allowlists need the worker IPs).
- **registry-credentials** requests read-write docker credentials valid for
  one hour and writes them to `.colors/<profile>/registry/push/config.json`
  (0600 in a 0700 directory); the credential is never printed.
- **cleanup** removes the kubeconfig and the push config after a delete.

The DigitalOcean token travels only as an HTTP header (`babashka.http-client`)
or the `DIGITALOCEAN_TOKEN` environment of the tofu process, never in argv
or a rendered file.

## The kubeconfig handoff

A consumer deployment on this cluster reads `.colors/<profile>/kubeconfig`
from the doks deployment directory (or runs `./green kubeconfig` there to
refresh it). It is a bearer credential: owner-only, generated, never
committed, dead after `delete`. Consumers must not copy it into their own
tracked files.

## Package and deployment coupling

The deployment launcher is a copy, not a symlink. The package pin is managed
only by `bb pin` after a clean pushed commit; never invent or hand-edit
`doks-sha`. After repinning, update consumers by installing/updating the skill
and re-copying the payload:

```sh
npx skills update -p -y
cp .agents/skills/package-doks-green/green green
```

A change spanning green, colors-compute, doks and a deployment is a separate
commit in each repository, pushed upstream first. For local cross-boundary
development, use `GREEN_LIB_ROOT`, `COLORS_COMPUTE_LIB_ROOT` or
`DOKS_LIB_ROOT` rather than editing a SHA.

`bb golden` renders two fixtures — DigitalOcean with a registry, Vultr
without — and protects the library documents, the registry stage, state keys
and the no-rendered-secret boundary. Read every golden diff; never run
`bb golden:accept` merely to make the check pass.

## Git

Work on the current branch. Do not commit or push unless explicitly asked.
