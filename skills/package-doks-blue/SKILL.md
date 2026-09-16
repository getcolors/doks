---
name: package-doks-blue
description: Provision and operate one managed Kubernetes cluster (DigitalOcean DOKS or Vultr VKE) with an optional integrated DigitalOcean container registry using Blue.
---

# doks Package Skill

Use the bundled `blue` launcher against a non-secret `colors.yml`.

```sh
./blue build
./blue create --dry-run
./blue create
./blue check
./blue kubeconfig
./blue registry
./blue delete
```

Read `references/configuration.md` before editing desired state. Put credentials
only in ignored `.envrc.private` as `COLORS_PAR_*`. Never export
`COLORS_PAR_PROFILE`, edit `.colors/`, weaken `compute-prevent-destroy`, or run
a real create/delete without authorization. `.colors/<profile>/kubeconfig` and
`.colors/<profile>/registry/push/config.json` are private generated
credentials; hand the kubeconfig path to consumer deployments, never its
contents.
