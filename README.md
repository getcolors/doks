# DOKS Colors Package

A Green workflow provisions one DigitalOcean Kubernetes cluster and its worker
pool. It owns no container registry, load balancer, or Redis infrastructure.
Requires Babashka, Git, OpenTofu >=1.10 and DigitalOcean/R2 credentials.

Copy `colors.example.yml` to `colors.yml`, set a currently supported DOKS version,
and run `bb doks build`, `bb doks create --dry-run`, then `bb doks create`.
`bb doks check` verifies cluster identity and readiness; `bb doks kubeconfig`
refreshes a private kubeconfig valid for 24 hours. `bb test` runs offline tests.

Use environment variables `COLORS_PAR_DO_TOKEN`,
`COLORS_PAR_DOKS_STATE_R2_ACCESS_KEY_ID`, and
`COLORS_PAR_DOKS_STATE_R2_SECRET_ACCESS_KEY`. Credentials are passed to OpenTofu
only through the child process environment, never rendered. The R2 state key is
`<profile>/cluster.tfstate`; S3 lockfiles serialize mutations. Keep the profile
and backend stable. The provider stores sensitive kubeconfig data in remote state;
restrict access to the state bucket.

Creation plans and applies with resource replacement protection enabled. Deletion
requires `COLORS_PAR_COMPUTE_PREVENT_DESTROY=false bb doks delete`. This only
destroys resources tracked in this deployment's state. Before deleting a cluster,
clean up operators and externally managed infrastructure deliberately. Never
force-unlock state without proving the previous operation stopped.

Generated files, private environment files, kubeconfig, state, and local caches
are ignored. Workflow calls are also available through `colors.doks/workflow`
and `green.workflow/run`. Cloud error output is suppressed to avoid leaking
provider diagnostics; exit status and the failed phase remain visible.
