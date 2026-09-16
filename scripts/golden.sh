#!/usr/bin/env bash
set -euo pipefail
# doks is a single colour, so there is no parity harness. This is the
# regression net in its place: render each fixture and diff against committed
# output — DigitalOcean with a registry, Vultr without.
#
#   ./scripts/golden.sh            check
#   ./scripts/golden.sh --accept   regenerate after an intended change — read
#                                  the diff first
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
status=0
for variant in digitalocean-registry vultr; do
  fixture="$tmp/$variant/colors.yml"
  mkdir -p "$tmp/$variant"
  sed "s#WORKDIR#$tmp/$variant/work#" "$root/test/fixtures/${variant%%-*}.yml" > "$fixture"
  DOKS_LIB_ROOT="$root" "$root/green" build -f "$fixture" >/dev/null
  profile=$(sed -n 's/^profile: //p' "$fixture")
  actual="$tmp/$variant/work/$profile"
  golden="$root/test/resources/golden/$variant/$profile"
  # No rendered artefact may carry a real secret into a committed golden.
  # Checked before --accept copies anything.
  if grep -rEq 'client-key-data|client-certificate-data|BEGIN (RSA |EC |OPENSSH |DSA )?PRIVATE KEY|dop_v1_|"auths"' "$actual"; then
    echo "golden: a credential-shaped value was rendered for $variant" >&2; exit 1
  fi
  if [[ ${1:-} == --accept ]]; then
    rm -rf "$golden"; mkdir -p "$(dirname "$golden")"; cp -a "$actual" "$golden"; continue
  fi
  [[ -d "$golden" ]] || { echo "golden missing for $variant; inspect build then run bb golden:accept" >&2; exit 1; }
  diff -ru "$golden" "$actual" || status=1
done
exit "$status"
