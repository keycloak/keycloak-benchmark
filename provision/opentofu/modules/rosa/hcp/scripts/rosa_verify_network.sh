#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -z "$CLUSTER_NAME" ]; then echo "Variable CLUSTER_NAME needs to be set."; exit 1; fi

CLUSTER=$(rosa describe cluster -c ${CLUSTER_NAME} -o json)
REGION=$(echo ${CLUSTER} | jq -r '.region.id')
SUBNETS=$(echo ${CLUSTER} | jq -r '.aws.subnet_ids | join(",")')
# Explicitly verify network as the initial "Inflight check" for egress regularly fails, but passes on subsequent verification
rosa verify network --cluster ${CLUSTER_NAME}
rosa verify network --watch --status-only --region ${REGION} --subnet-ids ${SUBNETS}
