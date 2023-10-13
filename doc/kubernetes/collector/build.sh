#!/bin/bash

set -ex

pwd

STARTDIR=$(dirname "$0")
BUILDDIR=${STARTDIR}/target/kubernetes/modules/ROOT/examples

mkdir -p ${BUILDDIR}/helm

# Those value match the Keycloak on ROSA Benchmark Key Results example
helm template --debug ${STARTDIR}/../../../provision/minikube/keycloak \
  --set hostname=minikube.nip.io \
  --set jvmDebug=false \
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
  | yq \
  > ${BUILDDIR}/helm/keycloak.yaml

# Those value match the Keycloak on ROSA Benchmark Key Results example
helm template --debug ${STARTDIR}/../../../provision/minikube/keycloak \
  --set hostname=minikube.nip.io \
  --set jvmDebug=false \
  --set cryostat=false \
  --set heapInitMB=64 \
  --set heapMaxMB=512 \
  --set infinispan.customConfig=true \
  --set infinispan.configFile=config/kcb-infinispan-cache-remote-store-config.xml \
  --set infinispan.remoteStore.enabled=true \
  --set infinispan.remoteStore.host=infinispan.keycloak.svc \
  --set infinispan.remoteStore.password=secure_password \
  --set infinispan.site=keycloak \
  | yq \
  > ${BUILDDIR}/helm/keycloak-ispn.yaml

