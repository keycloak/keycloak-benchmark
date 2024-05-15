#!/usr/bin/env bash
# Use this for deleting all Keycloak pods in sequence.
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

: ${PROJECT:="runner-keycloak"}

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Deleting all Keycloak pods in sequence\033[0m"

ALL_KC_PODS=$(kubectl -n "${PROJECT}" -o 'jsonpath={.items[*].metadata.name}' get pods -l app=keycloak | tr " " "\n")

ATTEMPT=0
for KC_POD in $ALL_KC_PODS; do
  ATTEMPT=$((ATTEMPT + 1))
  kubectl get pods -n "${PROJECT}" -l app=keycloak
  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Killing Pod '${KC_POD}'\033[0m"
  kubectl delete pod -n "${PROJECT}" "${KC_POD}" --grace-period=1

  START=$(date +%s)

  kubectl wait --for=condition=Available --timeout=120s deployments.apps/keycloak-operator -n "${PROJECT}" || true
  kubectl wait --for=condition=Ready --timeout=120s keycloaks.k8s.keycloak.org/keycloak -n "${PROJECT}" || true

  END=$(date +%s)
  DIFF=$(( END - START ))

  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Keycloak pod ${KC_POD} took ${DIFF} seconds to recover\033[0m"
done

kubectl get pods -n "${PROJECT}" -l app=keycloak
echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') All Keycloak pods have been processed.\033[0m"
