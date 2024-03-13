#!/usr/bin/env bash
set -e
cd $(dirname "${BASH_SOURCE[0]}")

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

REGION=$1
KCB_PARAMS=${@:2}

CLUSTER_NAME=${CLUSTER_NAME:-"benchmark_$(whoami)"}

if [ -f "env.yml" ]; then ANSIBLE_CUSTOM_VARS_ARG="-e @env.yml"; fi

GRAFANA_FROM_DATE_UNIX_MS=$(date +%s%3N)
ansible-playbook -i ${CLUSTER_NAME}_${REGION}_inventory.yml benchmark.yml \
  $ANSIBLE_CUSTOM_VARS_ARG \
  -e "kcb_params=\"${KCB_PARAMS}\"" || true
GRAFANA_TO_DATE_UNIX_MS=$(date +%s%3N)
SNAP_GRAFANA_TIME_WINDOW="from=${GRAFANA_FROM_DATE_UNIX_MS}&to=${GRAFANA_TO_DATE_UNIX_MS}"
echo "INFO: Input for snapGrafana.py is ${SNAP_GRAFANA_TIME_WINDOW}"
