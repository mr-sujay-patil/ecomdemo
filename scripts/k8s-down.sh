#!/usr/bin/env bash
# Phase 25: delete the local kind cluster, and with it every pod, volume and Secret in it.
# The images stay in Docker; `scripts/k8s-up.sh` recreates everything (with NEW database passwords,
# which is fine because the databases are recreated too).
set -euo pipefail
kind delete cluster --name ecomdemo
