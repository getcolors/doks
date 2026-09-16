#!/usr/bin/env bash
set -euo pipefail
# The payload launcher is the one file the test suite cannot reach: it is
# copied out of this repository into deployments. This proves the copy works
# on its own — a cold build of the fixture through the working-tree override,
# from the project root and from a subdirectory — and that the two refusals
# every deployment relies on still hold.
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
launcher="$root/skills/package-doks-green/green"
fail(){ echo "launcher: FAIL — $*" >&2; exit 1; }
grep -q 'io.github.getcolors.doks.workflow/workflow' "$launcher" || fail 'launcher no longer runs the library workflow'
grep -q 'def \^:private doks-sha' "$launcher" || fail 'launcher carries no pin site'
[[ -L "$root/green" && $(readlink "$root/green") == skills/package-doks-green/green ]] || fail './green is not a symlink to the payload'
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
cp "$launcher" "$tmp/green"; chmod +x "$tmp/green"
sed "s#WORKDIR#.colors#" "$root/test/fixtures/digitalocean.yml" > "$tmp/colors.yml"
(cd "$tmp" && DOKS_LIB_ROOT="$root" ./green build >/dev/null) || fail 'payload build failed'
for f in compute/managed-kubernetes/managed-kubernetes.tf.json compute/managed-kubernetes/backend.tf.json \
         doks-registry/registry.tf.json doks-registry/backend.tf.json; do
  [[ -f "$tmp/.colors/doks-fixture/$f" ]] || fail "build rendered no $f"
done
# The launcher walks up for colors.yml, so any subdirectory works.
mkdir -p "$tmp/nested/path"
(cd "$tmp/nested/path" && DOKS_LIB_ROOT="$root" ../../green build >/dev/null) || fail 'nested build failed'
[[ ! -d "$tmp/nested/path/.colors" ]] || fail 'nested build rendered beside the working directory instead of the desired state'
# A dry run walks the graph with no credentials and no side effects.
(cd "$tmp" && DOKS_LIB_ROOT="$root" ./green create --dry-run >/dev/null) || fail 'create --dry-run failed'
# The profile guard: an overlay would point one deployment at another's state.
out=$(cd "$tmp" && DOKS_LIB_ROOT="$root" COLORS_PAR_PROFILE=wrong ./green build 2>&1 || true)
grep -q COLORS_PAR_PROFILE <<<"$out" || fail 'COLORS_PAR_PROFILE was not refused'
[[ ! -d "$tmp/.colors/wrong" ]] || fail 'the overlaid profile rendered'
# The destroy guard holds even for a rehearsal.
set +e
(cd "$tmp" && DOKS_LIB_ROOT="$root" ./green delete --dry-run >/dev/null 2>&1); code=$?
set -e
[[ $code == 2 ]] || fail "delete --dry-run exited $code, expected the guard's 2"
# The Vultr fixture renders no registry stage.
rm -rf "$tmp/.colors"; sed "s#WORKDIR#.colors#" "$root/test/fixtures/vultr.yml" > "$tmp/colors.yml"
(cd "$tmp" && DOKS_LIB_ROOT="$root" ./green build >/dev/null) || fail 'vultr payload build failed'
[[ -f "$tmp/.colors/doks-fixture/compute/managed-kubernetes/managed-kubernetes.tf.json" ]] || fail 'vultr build rendered no compute document'
[[ ! -e "$tmp/.colors/doks-fixture/doks-registry" ]] || fail 'vultr build rendered a registry stage'
echo 'launcher: all checks passed'
