# DOKS package
Green-only package. Source is `src/colors/doks.clj`; test with `bb test`.
Configuration is non-secret `colors.yml`; credentials are COLORS_PAR_* env vars.
Never read generated `.colors` files as source, commit secrets/state/kubeconfig,
or change a profile to bypass an existing state. Live operations and Git pushes
require user authorization. The main workflow directly uses Green wf/run via its
CLI adapter. Keep provider and Green dependencies pinned.
