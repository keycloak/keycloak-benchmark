#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/accelerator_common.sh

requiredEnv ACCELERATOR_NAME CLUSTER_NAME KEYCLOAK_NAMESPACE

CLUSTER_NAME=${CLUSTER_NAME} ${SCRIPT_DIR}/../rosa_oc_login.sh > /dev/null

CLUSTER_REGION=$(rosa describe cluster -c ${CLUSTER_NAME} -o json | jq -r .region.id)
HOSTNAME=$(kubectl -n $KEYCLOAK_NAMESPACE get svc accelerator-loadbalancer --template="{{range .status.loadBalancer.ingress}}{{.hostname}}{{end}}")
LB_ARN=$(aws elbv2 describe-load-balancers \
  --query "LoadBalancers[?DNSName=='${HOSTNAME}'].LoadBalancerArn" \
  --region ${CLUSTER_REGION} \
  --output text
)

ACCELERATOR_ARN=$(aws globalaccelerator list-accelerators \
  --query "Accelerators[?Name=='${ACCELERATOR_NAME}'].AcceleratorArn" \
  --region us-west-2 \
  --output text
)
if [ -z "${ACCELERATOR_ARN}" ]; then
  echo "Unable to find Global Accelerator with Name '${ACCELERATOR_NAME}'"
  exit 1
fi

LISTENER_ARN=$(aws globalaccelerator list-listeners \
  --accelerator-arn ${ACCELERATOR_ARN} \
  --query "Listeners[*].ListenerArn" \
  --region us-west-2 \
  --output text
)
ENDPOINT_GROUPS=$(aws globalaccelerator list-endpoint-groups \
  --listener-arn ${LISTENER_ARN} \
  --query "EndpointGroups[0]" \
  --region us-west-2 \
  --output json \
)
ENDPOINT_GROUP_ARN=$(echo ${ENDPOINT_GROUPS} | jq -r '.EndpointGroupArn')
ENDPOINT_DESCRIPTIONS=$(echo ${ENDPOINT_GROUPS} | jq -r '.EndpointDescriptions | del(.[].HealthState)')

# Update the EndpointGroup only if the LB is missing from the configuration
if [[ -z $(echo ${ENDPOINT_DESCRIPTIONS} | jq ".[] | select(.EndpointId==\"${LB_ARN}\")") ]]; then
  CLUSTER_ENDPOINT="[
     {
         \"EndpointId\": \"${LB_ARN}\",
         \"Weight\": 128,
         \"ClientIPPreservationEnabled\": false
     }
  ]
  "
  UPDATED_ENDPOINT_DESCRIPTIONS=$(echo ${ENDPOINT_DESCRIPTIONS} | jq ". += ${CLUSTER_ENDPOINT}")

  aws globalaccelerator update-endpoint-group \
    --endpoint-group-arn ${ENDPOINT_GROUP_ARN} \
    --region us-west-2 \
    --endpoint-configurations "${UPDATED_ENDPOINT_DESCRIPTIONS}"
fi
