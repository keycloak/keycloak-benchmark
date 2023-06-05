#!/bin/bash +x
set -e
cd $(dirname "${BASH_SOURCE[0]}")

REGION=$1
KCB_PARAMS=${@:2}

CLUSTER_NAME=${CLUSTER_NAME:-"benchmark_$(whoami)"}

if [ -f "env.yml" ]; then CUSTOM_VARS_ARG="-e @env.yml"; fi

ansible-playbook -i ${CLUSTER_NAME}_${REGION}_inventory.yml benchmark.yml \
  $CUSTOM_VARS_ARG \
  -e "kcb_params=\"${KCB_PARAMS}\""
