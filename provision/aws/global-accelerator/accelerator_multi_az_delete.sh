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

requiredEnv ACCELERATOR_NAME

DELETE_LB=${DELETE_LB:=true}
if [ "${DELETE_LB}" = true ]; then
  requiredEnv CLUSTER_1 CLUSTER_2 KEYCLOAK_NAMESPACE

  deleteLoadBalancer ${CLUSTER_1} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE}
  deleteLoadBalancer ${CLUSTER_2} ${ACCELERATOR_LB_NAME} ${KEYCLOAK_NAMESPACE}
fi

ACCELERATOR_ARN=$(aws globalaccelerator list-accelerators \
  --query "Accelerators[?Name=='${ACCELERATOR_NAME}'].AcceleratorArn" \
  --output text
)

if [ -z "${ACCELERATOR_ARN}" ]; then
  echo "${ACCELERATOR_NAME} not found"
  exit 0
fi

aws globalaccelerator update-accelerator \
  --accelerator-arn ${ACCELERATOR_ARN} \
  --no-enabled

LISTENER_ARN=$(aws globalaccelerator list-listeners \
  --accelerator-arn ${ACCELERATOR_ARN} \
  --query "Listeners[0].ListenerArn" \
  --output text
)

if [[ "${LISTENER_ARN}" != "None" ]]; then
  ENDPOINT_GROUP_ARN=$(aws globalaccelerator list-endpoint-groups \
    --listener-arn ${LISTENER_ARN} \
    --query 'EndpointGroups[].EndpointGroupArn' \
    --output text
  )

  if [[ -n "${ENDPOINT_GROUP_ARN}" ]]; then
    aws globalaccelerator delete-endpoint-group \
      --endpoint-group-arn ${ENDPOINT_GROUP_ARN}
  fi

  aws globalaccelerator delete-listener \
    --listener-arn ${LISTENER_ARN}
fi

count=0
until acceleratorDisabled ${ACCELERATOR_ARN} || (( count++ >= 300 )); do
  sleep 1
done

if [ $count -gt 300 ]; then
  echo "Timeout waiting for accelerator ${ACCELERATOR_ARN} to be removed"
  exit 1
fi
aws globalaccelerator delete-accelerator --accelerator-arn ${ACCELERATOR_ARN}
