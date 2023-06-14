#!/bin/bash
# Use this for simulating failures of pods when testing Keycloak's capabilities to recover.
set -e

: ${INITIAL_DELAY_SECS:=30}
: ${CHAOS_DELAY_SECS:=60}
: ${PROJECT:="runner-keycloak"}

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Entering Chaos mode, with an initial delay of $INITIAL_DELAY_SECS seconds"
sleep $INITIAL_DELAY_SECS
echo -e "INFO:$(date '+%F-%T-%Z') Running Chaos scenario - Delete random Keycloak pod"
while true; do
  RANDOM_KC_POD=$(kubectl \
    -n "${PROJECT}" \
    -o 'jsonpath={.items[*].metadata.name}' \
    get pods -l app=keycloak | \
      tr " " "\n" | \
      shuf | \
      head -n 1)
  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Killing Pod '${RANDOM_KC_POD}' and waiting for ${CHAOS_DELAY_SECS} seconds"
  kubectl delete pod -n "${PROJECT}" "${RANDOM_KC_POD}" --grace-period=1
  sleep "${CHAOS_DELAY_SECS}"
  echo -e "\033[0m"
done

