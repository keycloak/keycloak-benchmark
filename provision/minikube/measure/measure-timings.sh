#!/bin/bash
#
# This script will:
#   - wait for the Keycloak resource to become ready
#   - for all Keycloak pods:
#     - record time differences between conditions: PodScheduled --> Initialized --> ContainersReady --> Ready
#     - record Keycloak server startup time and Quarkus augmentation time as reported in the pod log
#

set -e

export timestamp="${timestamp:-$(date -uIseconds)}"

echo "Waiting for the 'Ready' status of the Keycloak resource to become 'true'."
until [ "$(kubectl -n keycloak get keycloak/keycloak -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')" == "true" ]; do sleep 1; done
echo "The 'Ready' status of the Keycloak resource is now 'true'."

for pod in $(kubectl -n keycloak get pods -o name | grep -oP "keycloak-[0-9]+"); do 
  echo "Getting timing information for pod: '$pod'"
  csvFile="data/${timestamp}/${pod}/startup-times.csv"

  kubectl -n keycloak wait --for=condition=PodScheduled=true pod/${pod}
  podUID=$(kubectl -n keycloak get pods/${pod} -o jsonpath='{.metadata.uid}')
  if grep -q "$podUID" "$csvFile"; then
    echo "Timing information for pod '$pod' with UID '$podUID' already recorded in '$csvFile'. Skipping."
  else

    # Pod timings
    kubectl -n keycloak wait --for=condition=Initialized=true pod/${pod}
    kubectl -n keycloak wait --for=condition=ContainersReady=true pod/${pod}
    kubectl -n keycloak wait --for=condition=Ready=true pod/${pod}

    timePodScheduled=$(kubectl -n keycloak  get pods/${pod} -o jsonpath='{.status.conditions[?(@.type=="PodScheduled")].lastTransitionTime}')
    timeInitialized=$(kubectl -n keycloak  get pods/${pod} -o jsonpath='{.status.conditions[?(@.type=="Initialized")].lastTransitionTime}')
    timeContainersReady=$(kubectl -n keycloak  get pods/${pod} -o jsonpath='{.status.conditions[?(@.type=="ContainersReady")].lastTransitionTime}')
    timeReady=$(kubectl -n keycloak  get pods/${pod} -o jsonpath='{.status.conditions[?(@.type=="Ready")].lastTransitionTime}')

    if [ -z "$timePodScheduled" ]; then exit 1; fi
    if [ -z "$timeInitialized" ]; then exit 1; fi
    if [ -z "$timeContainersReady" ]; then exit 1; fi
    if [ -z "$timeReady" ]; then exit 1; fi

    podInitializationTime=$(( $( date -d "$timeInitialized" +%s ) - $( date -d "$timePodScheduled" +%s ) ))
    containersRedyingTime=$(( $( date -d "$timeContainersReady" +%s ) - $( date -d "$timeInitialized" +%s ) ))
    podReadyingTime=$(( $( date -d "$timeReady" +%s ) - $( date -d "$timeContainersReady" +%s ) ))

    # Keycloak server log timings
    keycloakAugmentationMillis=$(kubectl -n keycloak logs $pod | grep 'io.quarkus.deployment.QuarkusAugmentor' | grep -oP 'Quarkus augmentation completed in \K[0-9]+' | tail -n 1)
    if [ -z "$keycloakAugmentationMillis" ]; then
      keycloakAugmentationSeconds=""
    else
      keycloakAugmentationSeconds=$(echo "scale=3; $keycloakAugmentationMillis / 1000" | bc)
    fi

    keycloakStartedInSeconds=$(kubectl -n keycloak logs $pod | grep 'io.quarkus' | grep Keycloak | grep -oP 'started in \K[0-9\.]+' | tail -n 1)

    if [ ! -f "${csvFile}" ]; then
      mkdir -p "data/${timestamp}/${pod}"
      echo "Pod UID,Pod initialization time (s),Containers readying-time (s),Pod readying-time (s),Keycloak server augmentation time (s),Keycloak server startup time (s)" > "${csvFile}"
    fi
    echo "${podUID},${podInitializationTime},${containersRedyingTime},${podReadyingTime},${keycloakAugmentationSeconds},${keycloakStartedInSeconds}" >> "${csvFile}"

  fi

  echo
done