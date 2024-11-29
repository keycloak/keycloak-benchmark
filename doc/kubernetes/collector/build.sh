#!/bin/bash

set -ex

pwd

STARTDIR=$(dirname "$0")
BUILDDIR=${STARTDIR}/target/kubernetes/modules/ROOT/examples

mkdir -p ${BUILDDIR}/helm

# Those value match the Keycloak on ROSA Benchmark Key Results example
helm template --debug ${STARTDIR}/../../../provision/minikube/keycloak \
  --set hostname=minikube.nip.io \
  --set keycloakHostname=\<KEYCLOAK_URL_HERE\> \
  --set dbUrl=\<AWS_AURORA_URL_HERE\> \
  --set keycloakImage=\<KEYCLOAK_IMAGE_HERE\> \
  --set useAWSJDBCWrapper=true \
  --set multiSite=true \
  --set keycloakDocumentation=true \
  --set infinispan.customConfig=false \
  --set infinispan.remoteStore.enabled=false \
  --set infinispan.remoteStore.host=infinispan.keycloak.svc \
  --set infinispan.remoteStore.password=secure_password \
  --set infinispan.site=keycloak \
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
  | sed -E 's|[-A-Za-z0-9+/=]{1000,}|...|g' \
  > ${BUILDDIR}/helm/keycloak.yaml

# Those value match the Keycloak on ROSA Benchmark Key Results example
helm template --debug ${STARTDIR}/../../../provision/minikube/keycloak \
  --set hostname=minikube.nip.io \
  --set keycloakHostname=\<KEYCLOAK_URL_HERE\> \
  --set dbUrl=\<AWS_AURORA_URL_HERE\> \
  --set keycloakImage=\<KEYCLOAK_IMAGE_HERE\> \
  --set useAWSJDBCWrapper=true \
  --set multiSite=true \
  --set jvmDebug=false \
  --set cryostat=false \
  --set heapInitMB=64 \
  --set heapMaxMB=512 \
  --set infinispan.customConfig=false \
  --set infinispan.remoteStore.enabled=true \
  --set infinispan.remoteStore.host=infinispan.keycloak.svc \
  --set infinispan.remoteStore.password=secure_password \
  --set infinispan.site=keycloak \
  | yq \
  | sed -E 's|[-A-Za-z0-9+/=]{1000,}|...|g' \
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
  --set cpu= \
  --set memory= \
  --set jvmOptions="" \
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
  --set cacheDefaults.stateTransferMode=AUTO \
  --set cacheDefaults.xsiteFailurePolicy=FAIL \
  --set cacheDefaults.txMode=NON_DURABLE_XA \
  --set cacheDefaults.txLockMode=PESSIMISTIC \
  --set image= \
  --set fd.interval=2000 \
  --set fd.timeout=10000 \
  --set createSessionsCaches=false \
  --set acceleratorDNS=a3da6a6cbd4e27b02.awsglobalaccelerator.com \
  --set alertmanager.webhook.url=https://tjqr2vgc664b6noj6vugprakoq0oausj.lambda-url.eu-west-1.on.aws/ \
  --set alertmanager.webhook.username=keycloak \
  --set alertmanager.webhook.password=changme \
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
  --set cacheDefaults.stateTransferMode=AUTO \
  --set cacheDefaults.xsiteFailurePolicy=FAIL \
  --set cacheDefaults.txMode=NON_DURABLE_XA \
  --set cacheDefaults.txLockMode=PESSIMISTIC \
  --set image= \
  --set fd.interval=2000 \
  --set fd.timeout=10000 \
  --set createSessionsCaches=false \
  --set acceleratorDNS=a3da6a6cbd4e27b02.awsglobalaccelerator.com \
  --set alertmanager.webhook.url=https://tjqr2vgc664b6noj6vugprakoq0oausj.lambda-url.eu-west-1.on.aws/ \
  --set alertmanager.webhook.username=keycloak \
  --set alertmanager.webhook.password=changme \
  > ${BUILDDIR}/helm/ispn-site-b.yaml

# Infinispan volatile sessions
helm template --debug ${STARTDIR}/../../../provision/infinispan/ispn-helm \
  --set namespace=keycloak \
  --set replicas=3 \
  --set crossdc.enabled=true \
  --set crossdc.local.name=site-1 \
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
  --set cacheDefaults.stateTransferMode=AUTO \
  --set cacheDefaults.xsiteFailurePolicy=FAIL \
  --set cacheDefaults.txMode=NON_DURABLE_XA \
  --set cacheDefaults.txLockMode=PESSIMISTIC \
  --set image= \
  --set fd.interval=2000 \
  --set fd.timeout=10000 \
  --set createSessionsCaches=true \
  --set acceleratorDNS=a3da6a6cbd4e27b02.awsglobalaccelerator.com \
  --set alertmanager.webhook.url=https://tjqr2vgc664b6noj6vugprakoq0oausj.lambda-url.eu-west-1.on.aws/ \
  --set alertmanager.webhook.username=keycloak \
  --set alertmanager.webhook.password=changme \
  > ${BUILDDIR}/helm/ispn-volatile.yaml
