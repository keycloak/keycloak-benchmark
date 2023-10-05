#!/bin/bash

set -ex

pwd

STARTDIR=$(dirname "$0")
BUILDDIR=${STARTDIR}/target/kubernetes/modules/ROOT/examples

mkdir -p ${BUILDDIR}/helm

# Those value match the Keycloak on ROSA Benchmark Key Results example
helm template --debug ${STARTDIR}/../../../provision/minikube/keycloak \
  --set hostname=minikube.nip.io \
  --set jvmdebug=false \
  --set cryostat=false \
  --set instances=3 \
  --set cpuRequests=2 \
  --set cpuLimits=6 \
  --set memoryRequestsMB=1250 \
  --set memoryLimitsMB=2250 \
  --set heapInitMB=512 \
  --set heapMaxMB=1524 \
  --set dbPoolInitialSize=30 \
  --set dbPoolMaxSize=30 \
  --set dbPoolMinSize=30 \
  --set jvmDebug=false \
  | yq \
  > ${BUILDDIR}/helm/keycloak.yaml
