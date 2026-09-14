#!/usr/bin/env bash
set -euo pipefail
# Keep credential output in the operator's interactive terminal, never service/container logs.
deploy_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
test -f "$deploy_directory/project-deployer.yml"
deploy_options=(--config "$deploy_directory/project-deployer.yml")
deploy_dry_run=false
for deploy_argument in "$@"; do
  case "$deploy_argument" in
    --dry-run) deploy_dry_run=true; deploy_options+=(--dry-run --yes) ;;
    --yes|-y) deploy_options+=(--yes) ;;
    *) echo 'Usage: bash deploy.sh [--dry-run] [--yes]' >&2; exit 2 ;;
  esac
done
if ! "$deploy_dry_run" && { [ ! -t 0 ] || [ ! -t 1 ]; }; then
  echo 'Lance ce déploiement dans un terminal interactif pour afficher le token.' >&2
  exit 2
fi
cd -- "$deploy_directory"
pd "${deploy_options[@]}" deploy
pd "${deploy_options[@]}" op show-token
