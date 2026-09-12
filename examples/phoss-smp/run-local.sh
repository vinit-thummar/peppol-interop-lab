#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
state_dir="$script_dir/.state"
compose_file="$script_dir/compose.yml"

if docker compose version >/dev/null 2>&1; then
  compose=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  compose=(docker-compose)
else
  echo "Docker Compose is not installed." >&2
  exit 3
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop or Colima, then run this command again." >&2
  exit 3
fi

mkdir -p "$state_dir" "$script_dir/reports"
chmod 700 "$state_dir"
umask 077
printf '%s' 'password' > "$state_dir/phoss-smp-password"

case "$(uname -m)" in
  arm64 | aarch64)
    default_smp_image="phelger/phoss-smp-xml-arm64:8.4.3"
    ;;
  x86_64 | amd64)
    default_smp_image="phelger/phoss-smp-xml:8.4.3"
    ;;
  *)
    echo "Unsupported host architecture: $(uname -m)" >&2
    exit 2
    ;;
esac

export PHOSS_SMP_IMAGE="${PHOSS_SMP_IMAGE:-$default_smp_image}"
export PHOSS_SMP_PORT="${PHOSS_SMP_PORT:-8080}"
export PHOSS_SMP_KEYSTORE_PASSWORD="${PHOSS_SMP_KEYSTORE_PASSWORD:-peppol-lab-$RANDOM-$RANDOM}"
export LAB_UID="$(id -u)"
export LAB_GID="$(id -g)"

cleanup() {
  "${compose[@]}" -f "$compose_file" down --volumes --remove-orphans >/dev/null
  rm -f "$state_dir/phoss-smp-password"
  unset PHOSS_SMP_KEYSTORE_PASSWORD
}
trap cleanup EXIT

"${compose[@]}" -f "$compose_file" build peppol-lab
"${compose[@]}" -f "$compose_file" run --rm --no-deps smp-keystore-init
"${compose[@]}" -f "$compose_file" up -d --no-deps --wait --wait-timeout 180 phoss-smp
"${compose[@]}" -f "$compose_file" run --rm --no-deps peppol-lab
