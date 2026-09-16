---
name: package-doks-red
description: Provision and operate one managed Kubernetes cluster (DigitalOcean DOKS or Vultr VKE) with an optional integrated DigitalOcean container registry using Red.
---

# doks Package Skill

Use the bundled `red` launcher against a non-secret `colors.yml`.

```sh
./red build
./red create --dry-run
./red create
./red check
./red kubeconfig
./red registry
./red delete
```

Read `references/configuration.md` before editing desired state. Put credentials
only in ignored `.envrc.private` as `COLORS_PAR_*`. Never export
`COLORS_PAR_PROFILE`, edit `.colors/`, weaken `compute-prevent-destroy`, or run
a real create/delete without authorization. `.colors/<profile>/kubeconfig` and
`.colors/<profile>/registry/push/config.json` are private generated
credentials; hand the kubeconfig path to consumer deployments, never its
contents.
