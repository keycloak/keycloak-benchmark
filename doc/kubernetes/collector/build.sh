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


# Infinispan single cluster
helm template --debug ${STARTDIR}/../../../provision/infinispan/ispn-helm \
  --set namespace=keycloak \
  --set replicas=3 \
  --set crossdc.enabled=false \
  --set metrics.histograms=false \
  --set hotrodPassword="strong-password" \
  --set cacheDefaults.crossSiteMode=SYNC \
  > ${BUILDDIR}/helm/ispn-single.yaml

# Infinispan site A deployment
helm template --debug ${STARTDIR}/../../../provision/infinispan/ispn-helm \
  --set namespace=keycloak \
  --set replicas=3 \
  --set crossdc.enabled=true \
  --set crossdc.local.name=site-a \
  --set crossdc.local.gossipRouterEnabled=true \
  --set crossdc.remote.name=site-b \
  --set crossdc.remote.gossipRouterEnabled=true \
  --set crossdc.remote.namespace=keycloak \
  --set crossdc.remote.url=openshift://api.site-b \
  --set crossdc.remote.secret=xsite-token-secret \
  --set crossdc.route.enabled=true \
  --set crossdc.route.tls.keystore.secret=xsite-keystore-secret \
  --set crossdc.route.tls.truststore.secret=xsite-truststore-secret \
  --set metrics.histograms=false \
  --set hotrodPassword="strong-password" \
  --set cacheDefaults.crossSiteMode=SYNC \
  > ${BUILDDIR}/helm/ispn-site-a.yaml

# Infinispan site B deployment
helm template --debug ${STARTDIR}/../../../provision/infinispan/ispn-helm \
  --set namespace=keycloak \
  --set replicas=3 \
  --set crossdc.enabled=true \
  --set crossdc.local.name=site-b \
  --set crossdc.local.gossipRouterEnabled=true \
  --set crossdc.remote.name=site-a \
  --set crossdc.remote.gossipRouterEnabled=true \
  --set crossdc.remote.namespace=keycloak \
  --set crossdc.remote.url=openshift://api.site-a \
  --set crossdc.remote.secret=xsite-token-secret \
  --set crossdc.route.enabled=true \
  --set crossdc.route.tls.keystore.secret=xsite-keystore-secret \
  --set crossdc.route.tls.truststore.secret=xsite-truststore-secret \
  --set metrics.histograms=false \
  --set hotrodPassword="strong-password" \
  --set cacheDefaults.crossSiteMode=SYNC \
  > ${BUILDDIR}/helm/ispn-site-b.yaml
