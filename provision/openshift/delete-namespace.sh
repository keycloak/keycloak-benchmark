#!/usr/bin/env bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

: ${PROJECT:="runner-keycloak"}

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') Deleting the ${PROJECT} namespace \033[0m"

oc delete namespace ${PROJECT}

while oc get project ${PROJECT} &> /dev/null; do
  echo "Waiting for namespace ${PROJECT} to be deleted..."
  sleep 2
done

echo -e "\033[0;31mINFO:$(date '+%F-%T-%Z') ${PROJECT} namespace is now deleted successfully.\033[0m"
