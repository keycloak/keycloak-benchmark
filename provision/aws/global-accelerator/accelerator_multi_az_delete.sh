#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/accelerator_common.sh

function deleteLoadBalancer() {
  export CLUSTER_NAME=$1
  SVC_NAME=$2
  NAMESPACE=$3

  bash ${SCRIPT_DIR}/../rosa_oc_login.sh > /dev/null
  oc delete -n ${NAMESPACE} svc ${SVC_NAME} || true
}

if [ -z "${ACCELERATOR_NAME}" ]; then
  if [ -z "${ACCELERATOR_DNS}" ]; then
    echo "ACCELERATOR_NAME or ACCELERATOR_DNS must be set"
    exit 1
  fi
  ACCELERATOR_NAME=$(aws globalaccelerator list-accelerators \
    --query "Accelerators[?(ends_with(DnsName, '${ACCELERATOR_DNS}') || ends_with(DualStackDnsName, '${ACCELERATOR_DNS}'))].Name" \
    --output text
  )
  if [ -z "${ACCELERATOR_NAME}" ]; then
    echo "Unable to find Global Accelerator with DnsName '${ACCELERATOR_DNS}'"
    exit 0
  fi
fi

cd ${SCRIPT_DIR}/../../opentofu/modules/aws/accelerator
bash ${SCRIPT_DIR}/../../opentofu/destroy.sh ${ACCELERATOR_NAME}

DELETE_LB=${DELETE_LB:=true}
if [ "${DELETE_LB}" = true ]; then
  requiredEnv CLUSTER_1 CLUSTER_2 KEYCLOAK_NAMESPACE

  deleteLoadBalancer ${CLUSTER_1} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE}
  deleteLoadBalancer ${CLUSTER_2} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE}
fi
