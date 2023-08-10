#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

EXISTING_MACHINE_CIDRS=$(rosa list clusters -o json | jq -r ".[].network.machine_cidr" | sort -u)

if (( $(echo ${EXISTING_MACHINE_CIDRS} | wc -l) > 255 )); then
  echo "Maximum number of unique machine CIDRS reached"
  echo ${EXISTING_MACHINE_CIDRS}
  exit 1
fi

while true; do
  CIDR="10.$(shuf -i 0-255 -n 1).0.0/16"
  if [[ "${EXISTING_MACHINE_CIDRS}" != *"${CIDR}"* ]]; then
    break
  fi
done
echo ${CIDR}
