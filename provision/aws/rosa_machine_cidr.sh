#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

# https://access.redhat.com/documentation/en-us/red_hat_openshift_service_on_aws/4/html/networking/cidr-range-definitions
# Must not overlap with Pod CDR: 10.128.0.0/14
# Must not overlap with OVN Kubernetes: 100.64.0.0/16

EXISTING_MACHINE_CIDRS=$(rosa list clusters -o json | jq -r ".[].network.machine_cidr" | sort -u)

if (( $(echo ${EXISTING_MACHINE_CIDRS} | wc -l) > 63 )); then
  echo "Maximum number of unique machine CIDRS reached"
  echo ${EXISTING_MACHINE_CIDRS}
  exit 1
fi

while true; do
  CIDR="10.0.$(shuf -i 0-63 -n 1).0/24"
  if [[ "${EXISTING_MACHINE_CIDRS}" != *"${CIDR}"* ]]; then
    break
  fi
done
echo ${CIDR}
