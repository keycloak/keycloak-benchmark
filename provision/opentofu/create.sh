#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

WORKSPACE=$1
TOFU_CMD=$2

echo "Workspace: ${WORKSPACE}"
tofu init -upgrade
tofu workspace new ${WORKSPACE} || echo "Workspace ${WORKSPACE} already exists"
export TF_WORKSPACE=${WORKSPACE}
echo ${TOFU_CMD}
${TOFU_CMD}
