#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
run_launcher() {
  local color=$1; shift
  if [[ "$color" == blue && ${DOKS_COLD_LAUNCHERS:-0} != 1 ]]; then
    DOKS_LIB_ROOT="$root" uv run --project "$root/blue" python "$tmp/$color/$color" "$@"
  elif [[ ${DOKS_COLD_LAUNCHERS:-0} == 1 ]]; then
    env -u DOKS_LIB_ROOT "$tmp/$color/$color" "$@"
  else
    DOKS_LIB_ROOT="$root" "$tmp/$color/$color" "$@"
  fi
}
for color in red blue; do
  [[ -L "$root/$color/$color" ]]
  mkdir -p "$tmp/$color/nested/path"
  cp "$root/skills/package-doks-$color/$color" "$tmp/$color/$color"
  for provider in digitalocean vultr; do
    sed 's#WORKDIR#.colors#' "$root/test/fixtures/$provider.yml" > "$tmp/$color/colors.yml"
    (cd "$tmp/$color/nested/path" && run_launcher "$color" build >/dev/null)
    [[ -f "$tmp/$color/.colors/doks-fixture/compute/managed-kubernetes/managed-kubernetes.tf.json" ]]
    [[ ! -d "$tmp/$color/nested/path/.colors" ]]
    for event in create check kubeconfig; do
      (cd "$tmp/$color" && run_launcher "$color" "$event" --dry-run >/dev/null)
    done
    set +e
    (cd "$tmp/$color" && run_launcher "$color" delete --dry-run >/dev/null 2>&1)
    code=$?
    set -e
    [[ $code == 2 ]]
    set +e
    output=$(cd "$tmp/$color" && COLORS_PAR_PROFILE=wrong run_launcher "$color" build 2>&1)
    code=$?
    set -e
    [[ $code == 2 && "$output" == *COLORS_PAR_PROFILE* ]]
    [[ ! -d "$tmp/$color/.colors/wrong" ]]
    if [[ "$provider" == vultr ]]; then
      [[ ! -e "$tmp/$color/.colors/doks-fixture/doks-registry" ]]
    fi
    rm -rf "$tmp/$color/.colors"
  done
done
echo 'launcher: Red and Blue copied payloads passed'
