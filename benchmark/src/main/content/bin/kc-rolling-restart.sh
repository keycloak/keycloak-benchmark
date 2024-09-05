#!/usr/bin/env bash
# Use this for deleting all Keycloak or Infinispan pods in sequence.
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

: ${PROJECT:="runner-keycloak"}

# Ensure POD_LABEL is set
if [[ -z "${POD_LABEL}" ]]; then
  echo "POD_LABEL is not set. Please export POD_LABEL before running the script."
  exit 1
fi

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Deleting all pods with label '${POD_LABEL}' in sequence\033[0m"

ALL_PODS=$(kubectl -n "${PROJECT}" -o 'jsonpath={.items[*].metadata.name}' get pods -l app="${POD_LABEL}" | tr " " "\n")

ATTEMPT=0
for POD in $ALL_PODS; do
  ATTEMPT=$((ATTEMPT + 1))
  kubectl get pods -n "${PROJECT}" -l app="${POD_LABEL}"
  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Killing Pod '${POD}'\033[0m"
  kubectl delete pod -n "${PROJECT}" "${POD}"

  START=$(date +%s)

  if [[ "$POD_LABEL" == "keycloak" ]]; then
    kubectl wait --for=condition=Available --timeout=120s deployments.apps/keycloak-operator -n "${PROJECT}" || true
    kubectl wait --for=condition=Ready --timeout=120s keycloaks.k8s.keycloak.org/keycloak -n "${PROJECT}" || true
  elif [[ "$POD_LABEL" == "infinispan-pod" ]]; then
    kubectl wait --for condition=WellFormed --timeout=120s infinispans.infinispan.org -n "${PROJECT}" infinispan || true
  fi

  END=$(date +%s)
  DIFF=$(( END - START ))

  echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') ${POD} pod took ${DIFF} seconds to recover\033[0m"
done

kubectl get pods -n "${PROJECT}" -l app="${POD_LABEL}"
echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') All ${POD_LABEL} pods have been restarted.\033[0m"
