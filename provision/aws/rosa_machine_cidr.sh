#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function freeCidr() {
  if (( $(echo ${EXISTING_MACHINE_CIDRS} | wc -l) > 63 )); then
    echo "Maximum number of unique machine CIDRS reached"
    echo ${EXISTING_MACHINE_CIDRS}
    exit 1
  fi

  while true; do
    CIDR="10.0.$(shuf -i 0-63 -n 1).0/24"
    if [[ "${EXISTING_MACHINE_CIDRS}" != *"${CIDR}"* ]]; then
      echo ${CIDR}
      break
    fi
  done
}

function cidr() {
  CLUSTER_NAME=$1
  EXISTING_MACHINE_CIDRS=$2
  CLUSTER=$(echo $CLUSTERS | jq ".[] | select(.name == \"${CLUSTER_NAME}\")")
  if [ -n "${CLUSTER}" ]; then
    echo ${CLUSTER} | jq -r .network.machine_cidr
  else
    EXISTING_MACHINE_CIDRS="$(echo ${CLUSTERS} | jq -r ".[].network.machine_cidr" | sort -u)\n${EXISTING_MACHINE_CIDRS}"
    freeCidr
  fi
}

if [ -z "$CLUSTER_PREFIX" ]; then echo "'CLUSTER_PREFIX' needs to be present in input JSON."; exit 1; fi

# https://access.redhat.com/documentation/en-us/red_hat_openshift_service_on_aws/4/html/networking/cidr-range-definitions
# Must not overlap with Pod CDR: 10.128.0.0/14
# Must not overlap with OVN Kubernetes: 100.64.0.0/16

CLUSTERS=$(rosa list clusters -o json)
CIDR_A=$(cidr "${CLUSTER_PREFIX}-a")
CIDR_B=$(cidr "${CLUSTER_PREFIX}-b" ${CIDR_A})
echo "{\"cidr_a\":\"${CIDR_A}\",\"cidr_b\":\"${CIDR_B}\"}"
