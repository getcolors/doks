#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for provider in digitalocean vultr; do
  for color in green red blue; do
    mkdir -p "$tmp/$provider/$color"
    sed "s#WORKDIR#$tmp/$provider/$color/work#" "$root/test/fixtures/$provider.yml" > "$tmp/$provider/$color/colors.yml"
    case "$color" in
      green) DOKS_LIB_ROOT="$root" "$root/green" build -f "$tmp/$provider/$color/colors.yml" >/dev/null ;;
      red) DOKS_LIB_ROOT="$root" "$root/red/red" build -f "$tmp/$provider/$color/colors.yml" >/dev/null ;;
      blue) uv run --project "$root/blue" python -m package_doks_blue build -f "$tmp/$provider/$color/colors.yml" >/dev/null ;;
    esac
  done
  diff -ru "$tmp/$provider/green/work" "$tmp/$provider/red/work"
  diff -ru "$tmp/$provider/green/work" "$tmp/$provider/blue/work"
done
echo 'parity: DigitalOcean registry and Vultr fixtures match in all colours'
