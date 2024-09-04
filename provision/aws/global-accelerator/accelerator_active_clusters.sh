#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/accelerator_common.sh

requiredEnv ACCELERATOR_NAME

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
ENDPOINT_ARNS=$(aws globalaccelerator list-endpoint-groups \
  --listener-arn ${LISTENER_ARN} \
  --query "EndpointGroups[0].EndpointDescriptions[*].EndpointId" \
  --region us-west-2 \
  --output text
)
ENDPOINT_REGION=$(echo ${ENDPOINT_ARNS} | cut -d ':' -f4)

aws elbv2 describe-tags \
  --resource-arns ${ENDPOINT_ARNS} \
  --query "TagDescriptions[*].Tags[?Key=='site'].Value" \
  --region ${ENDPOINT_REGION} \
  --output text | xargs -n1 | sort | xargs
