#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

WORKSPACE=$1
echo ${WORKSPACE}
tofu init
if tofu workspace select ${WORKSPACE}; then
  tofu state pull
  OUTPUTS=$(tofu output)
  echo "${OUTPUTS}"
  INPUTS=$(echo "${OUTPUTS}" | sed -n 's/input_//p' | sed 's/ //g' | sed 's/^/-var /' | tr -d '"')
  DESTROY_CMD="tofu destroy -auto-approve ${INPUTS} -lock-timeout=15m"
  echo ${DESTROY_CMD}
  ${DESTROY_CMD}
  tofu state list
  tofu workspace select default
  tofu workspace delete ${WORKSPACE}
  echo "Workspace ${WORKSPACE} is deleted."
fi
