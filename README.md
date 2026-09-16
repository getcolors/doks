# doks

A Green Package Skill for one managed Kubernetes cluster — DigitalOcean DOKS
or Vultr VKE — through the pinned colors-compute library, with an optional
deployment-owned DigitalOcean container registry integrated with the cluster
so every namespace can pull from it.

```sh
./green build                # render only, no credentials
./green create --dry-run
./green create
./green check                # nodes Ready, registry present and integrated
./green kubeconfig           # refresh .colors/<profile>/kubeconfig
./green registry             # one-hour docker push config under .colors/<profile>/registry/push/
./green delete               # guarded by compute-prevent-destroy
```

Install with `npx skills add getcolors/doks`, then copy
`.agents/skills/package-doks-green/green` to the deployment root. Credentials
are `COLORS_PAR_*` exports in `.envrc.private` (`COLORS_PAR_DO_TOKEN` or
`COLORS_PAR_VULTR_API_KEY`, `COLORS_PAR_R2_ACCESS_KEY_ID`,
`COLORS_PAR_R2_SECRET_ACCESS_KEY`); never set `COLORS_PAR_PROFILE`. See
`skills/package-doks-green/references/configuration.md` for every key; the
in-repo `colors.yml` is a worked example.

The cluster and the registry are both named after the profile. State lives in
the configured R2 or S3 bucket under `<profile>/compute/managed-kubernetes.tfstate`
(library-owned) and `<profile>/registry.tfstate` (package-owned). Deletion
requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false` for one run and removes
the registry integration, the cluster, then the registry.

## Development

```sh
bb test
bb golden
./scripts/launcher.sh
```

`bb pin` stamps the payload launcher with the pushed HEAD after a clean
commit; `DOKS_LIB_ROOT=/path/to/doks` points a copied launcher at a working
tree meanwhile.
