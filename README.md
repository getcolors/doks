# doks

DOKS is a Package Skill with native Green, Red and Blue implementations.
It provisions one DigitalOcean DOKS or Vultr VKE cluster through the pinned
colors-compute library. An optional DigitalOcean container registry integrates
with the cluster so every namespace can pull images from it.

| Skill | Runtime | Checkout launcher |
|---|---|---|
| `package-doks-green` | Babashka | `./green` |
| `package-doks-red` | Bun / TypeScript | `./red/red` |
| `package-doks-blue` | uv / Python | `./blue/blue` |

All colours accept the same `colors.yml`, credentials and six verbs. The
examples use Green; an installed Red or Blue deployment uses `./red` or `./blue`.

```sh
./green build                # render only, no credentials
./green create --dry-run
./green create
./green check                # nodes Ready, registry present and integrated
./green kubeconfig           # refresh .colors/<profile>/kubeconfig
./green registry             # one-hour docker push config under .colors/<profile>/registry/push/
./green delete               # guarded by compute-prevent-destroy
```

Choose a skill explicitly and copy its launcher to the deployment root:

```sh
npx skills add getcolors/doks --skill package-doks-red
cp .agents/skills/package-doks-red/red ./red
./red build
./red create --dry-run
```

For Blue, select `package-doks-blue` and copy its `blue` file. For Green,
select `package-doks-green` and copy its `green` file. Repeat the copy after
`npx skills update -p`; the installed payload and deployment launcher are
separate files. Credentials
are `COLORS_PAR_*` exports in `.envrc.private` (`COLORS_PAR_DO_TOKEN` or
`COLORS_PAR_VULTR_API_KEY`, `COLORS_PAR_R2_ACCESS_KEY_ID`,
`COLORS_PAR_R2_SECRET_ACCESS_KEY`); never set `COLORS_PAR_PROFILE`. See
`skills/package-doks-green/references/configuration.md` for every key; the
in-repo `colors.yml` is a worked example.

The cluster and the registry are both named after the profile. State lives in
the configured R2 or S3 bucket under `<profile>/compute/managed-kubernetes.tfstate`
(library-owned) and `<profile>/registry.tfstate` (package-owned). Deletion
requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for one run and removes
the registry integration, the cluster, then the registry, printing one line
per completed stage. DigitalOcean removes the worker Droplets and the
`k8s-<cluster-id>-*` firewalls asynchronously over the following minutes, so
they remain visible briefly after `delete` returns and `check` is expected to
fail from then on.

## Development

```sh
bb test
bb golden
bun install --cwd red
bun test --cwd red
bun run --cwd red typecheck
uv sync --project blue
uv run --project blue pytest blue/tests
./scripts/parity.sh
./scripts/launcher.sh
```

`bb pin` stamps all three payload launchers with the pushed HEAD after a clean
commit; `DOKS_LIB_ROOT=/path/to/doks` points a copied launcher at a working
tree meanwhile.

The parity check compares both provider fixtures byte for byte across all
colours. Tests cover ownership refusals, registry integration, node readiness,
private credential files and deletion order. No check provisions infrastructure.
After publishing and stamping pins, `DOKS_COLD_LAUNCHERS=1 ./scripts/launcher-ports.sh`
also checks copied Red and Blue launchers without a working-tree override.
