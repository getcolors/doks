# CLAUDE.md

## What this is

`doks` is a Green, Red and Blue Package Skill for one managed Kubernetes cluster —
DigitalOcean Kubernetes (DOKS) or Vultr Kubernetes Engine (VKE) — provisioned
through the pinned `colors-compute` library's `managed-kubernetes` kind, plus
an optional deployment-owned DigitalOcean container registry integrated with
the cluster. It creates no machines: the Compute Provider Standard's machine
API, the cluster standard and the SSH standards do not apply. The first
consumer is `../doks-dev`; the cluster it materializes is what
`redis-operator-doks` and similar deployments run on.

The repository ships `package-doks-green`, `package-doks-red` and
`package-doks-blue`. Green source remains under `src/clj`; Red source is in
`red/src`; Blue source is in `blue/src/package_doks_blue`. Each implementation
uses its native SDK workflow and the matching colors-compute library.
The root `./green`, `./red/red` and `./blue/blue` link to the payloads.
Copied deployment launchers contain only dependency bootstrap and CLI dispatch.

## Commands

```sh
bb test                      # unit tests
bun install --cwd red
bun test --cwd red
bun run --cwd red typecheck
uv sync --project blue
uv run --project blue pytest blue/tests
./scripts/parity.sh           # byte-for-byte Green / Red / Blue fixture comparison
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
bb pin                       # stamp all payloads with the pushed HEAD — never by hand
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
- **cleanup** removes the kubeconfig and the push config after a delete,
  then the rest of `.colors/<profile>/registry/` on a best-effort basis:
  files another user owns (a container build run as root leaves its
  `buildx/` state beside the push config) are reported, never thrown. Each
  delete stage prints one summary line (integration removed, cluster
  destroyed, registry destroyed, cleanup done) so a failure after
  destruction is unambiguous.

Observed live on DigitalOcean: after the library's destroy returns (about
15 s), the DOKS worker Droplet and the two `k8s-<cluster-id>-*` firewalls
stay visible in the account for several minutes while DigitalOcean cleans
them up asynchronously. `delete` says so in its final line; `check` after a
delete is expected to fail.

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
only by `bb pin` after a clean commit pushed to main. It reads native SDK and
compute pins from the colour manifests and stamps all three launchers. Never
invent or hand-edit a pin. After repinning, update consumers by installing/updating the skill
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

## Port contracts

All colours implement build, create, check, kubeconfig, registry and delete.
They use the same legacy-state refusal, state keys, registry resource and
credential permissions. Dry runs validate the profile and destruction guard,
then skip every effect, including rendering. The package validates provider
choices from the compute library recipes. Registry tokens travel in HTTP
headers or the tofu environment and never appear in generated documents.

Use `DOKS_LIB_ROOT` for copied-launcher development. Red resolves the checkout's
`red/src/index.ts` after `bun install --cwd red`. To run an unpinned Blue copy,
use `DOKS_LIB_ROOT=/path/to/doks uv run --project blue python /path/to/blue ...`.
After pinning, the standalone Blue executable installs its immutable dependencies
through uv. `DOKS_COLD_LAUNCHERS=1 ./scripts/launcher-ports.sh` checks published
payloads without a working-tree override.
