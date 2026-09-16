---
name: package-doks-green
description: Provision and operate one managed Kubernetes cluster (DigitalOcean DOKS or Vultr VKE) with an optional integrated DigitalOcean container registry using Green.
---

# doks Package Skill

Use the bundled `green` launcher against a non-secret `colors.yml`.

```sh
./green build
./green create --dry-run
./green create
./green check
./green kubeconfig
./green registry
./green delete
```

Read `references/configuration.md` before editing desired state. Put credentials
only in ignored `.envrc.private` as `COLORS_PAR_*`. Never export
`COLORS_PAR_PROFILE`, edit `.colors/`, weaken `compute-prevent-destroy`, or run
a real create/delete without authorization. `.colors/<profile>/kubeconfig` and
`.colors/<profile>/registry/push/config.json` are private generated
credentials; hand the kubeconfig path to consumer deployments, never its
contents.
