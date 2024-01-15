#!/usr/bin/env bash
# Use this for simulating failures of pods when testing Keycloak's capabilities to recover.
set -e

: ${INITIAL_DELAY_SECS:=30}
: ${CHAOS_DELAY_SECS:=10}
: ${PROJECT:="runner-keycloak"}

LOGS_DIR=$1

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Entering Chaos mode, with an initial delay of $INITIAL_DELAY_SECS seconds\033[0m"
sleep $INITIAL_DELAY_SECS
echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Running Chaos scenario - Delete random Keycloak pod\033[0m"

ATTEMPT=0
while true; do
  ATTEMPT=$[ATTEMPT + 1]
  RANDOM_KC_POD=$(kubectl \
    -n "${PROJECT}" \
    -o 'jsonpath={.items[*].metadata.name}' \
    get pods -l app=keycloak | \
      tr " " "\n" | \
      shuf | \
      head -n 1)

  kubectl get pods -n "${PROJECT}" -l app=keycloak -o wide
  kubectl logs -f -n "${PROJECT}" "${RANDOM_KC_POD}" > "$LOGS_DIR/${ATTEMPT}-${RANDOM_KC_POD}.log" 2>&1 &
  kubectl describe -n "${PROJECT}" pod "${RANDOM_KC_POD}" > "$LOGS_DIR/${ATTEMPT}-${RANDOM_KC_POD}-complete-resource.log" 2>&1
  kubectl top -n "${PROJECT}" pod -l app=keycloak --sum=true > "$LOGS_DIR/${ATTEMPT}-top.log" 2>&1
  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Killing Pod '${RANDOM_KC_POD}' and waiting for ${CHAOS_DELAY_SECS} seconds\033[0m"
  kubectl delete pod -n "${PROJECT}" "${RANDOM_KC_POD}" --grace-period=1

  START=$(date +%s)

  kubectl wait --for=condition=Available --timeout=600s deployments.apps/keycloak-operator -n "${PROJECT}" || true
  kubectl wait --for=condition=Ready --timeout=600s keycloaks.k8s.keycloak.org/keycloak -n "${PROJECT}" || true

  END=$(date +%s)
  DIFF=$(( END - START ))

  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Keycloak pod ${RANDOM_KC_POD} took ${DIFF} seconds to recover\033[0m"
  sleep "${CHAOS_DELAY_SECS}"
done
