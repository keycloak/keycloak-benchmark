#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

WORKSPACE=$1
echo "Workspace: ${WORKSPACE}"
tofu init
if tofu workspace select ${WORKSPACE}; then
  tofu state pull
  OUTPUTS=$(tofu output)
  echo "${OUTPUTS}"
  INPUTS=$(echo "${OUTPUTS}" | sed -n 's/input_//p' | sed 's/ //g' | sed 's/^/-var /' | tr -d '"')
  # CLUSTER_ADMIN_PASSWORD is not necessary for cluster deletion but is required by the 'tofu destroy' command anyway
  # See: https://github.com/hashicorp/terraform/issues/18994
  if [ -z "$CLUSTER_ADMIN_PASSWORD" ]; then echo "CLUSTER_ADMIN_PASSWORD needs to be set."; exit 1; fi
  DESTROY_CMD="tofu destroy -auto-approve ${INPUTS} -var cluster_admin_password=\"$CLUSTER_ADMIN_PASSWORD\" -lock-timeout=15m"
  echo ${DESTROY_CMD}
  ${DESTROY_CMD}
  tofu state list
  tofu workspace select default
  tofu workspace delete ${WORKSPACE}
  echo "Workspace ${WORKSPACE} is deleted."
fi
