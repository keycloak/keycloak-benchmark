#!/usr/bin/env bash
set -e
for pod in $(kubectl -n keycloak get pods -o name | grep -oP "keycloak-[0-9]+"); do 
  echo "pods/${pod} .spec.containers[0].image:            $(kubectl -n keycloak get pods/${pod} -o jsonpath='{.spec.containers[0].image}')"
  echo "pods/${pod} .status.containerStatuses[0].image:   $(kubectl -n keycloak get pods/${pod} -o jsonpath='{.status.containerStatuses[0].image}')"
  echo "pods/${pod} .status.containerStatuses[0].imageID: $(kubectl -n keycloak get pods/${pod} -o jsonpath='{.status.containerStatuses[0].imageID}')"
done