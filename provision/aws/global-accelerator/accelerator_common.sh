#!/bin/bash
set -e

function requiredEnv() {
  for ENV in $@; do
      if [ -z "${!ENV}" ]; then
        echo "${ENV} variable must be set"
        exit 1
      fi
  done
}

function acceleratorDisabled() {
    ACCELERATOR=$(aws globalaccelerator describe-accelerator \
      --accelerator-arn $1 \
      --query Accelerator \
      --region us-west-2
    )
    ENABLED=$(echo ${ACCELERATOR} | jq -r .Enabled)
    STATUS=$(echo ${ACCELERATOR} | jq -r .Status)
    [[ ${ENABLED} == "false" && ${STATUS} == "DEPLOYED" ]]
}

export AWS_PAGER=""
# us-west-2 must be used for all global accelerator commands
export AWS_REGION=us-west-2
export ACCELERATOR_LB_NAME=${ACCELERATOR_LB_NAME:-"accelerator-loadbalancer"}
