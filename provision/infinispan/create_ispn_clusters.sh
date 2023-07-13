#!/bin/bash

set -e
set -x

WD=$(dirname $0)

# Required options set in commons.sh
. ${WD}/commons.sh

# Options
XSITE_SERVICE_ACCOUNT=${XSITE_SERVICE_ACCOUNT:-"xsite-sa"}
XSITE_TOKEN_SECRET=${XSITE_TOKEN_SECRET:-"xsite-token-secret"}
XSITE_KS_TLS_SECRET=${XSITE_KS_TLS_SECRET:-"xsite-keystore-secret"}
XSITE_TS_TLS_SECRET=${XSITE_TS_TLS_SECRET:-"xsite-trustatore-secret"}
XSITE_MODE=${XSITE_MODE:-"SYNC"}
ISPN_REPLICAS=${ISPN_REPLICAS:-"2"}


function create_tls_secrets() {
  local kubecfg="${1}"
  local namespace="${2}"
  local certs_path="${WD}/certs"
  local ks_args="--from-file=keystore.p12="${certs_path}/keystore.p12" --from-literal=password=secret --from-literal=type=pkcs12"
  local ts_args="--from-file=truststore.p12="${certs_path}/truststore.p12" --from-literal=password=caSecret --from-literal=type=pkcs12"
  KUBECONFIG="${kubecfg}" oc -n "${namespace}" delete secret "${XSITE_KS_TLS_SECRET}" || true
  KUBECONFIG="${kubecfg}" oc -n "${namespace}" delete secret "${XSITE_TS_TLS_SECRET}" || true
  KUBECONFIG="${kubecfg}" oc -n "${namespace}" create secret generic "${XSITE_KS_TLS_SECRET}" ${ks_args}
  KUBECONFIG="${kubecfg}" oc -n "${namespace}" create secret generic "${XSITE_TS_TLS_SECRET}" ${ts_args}
}

function create_service_account() {
  local kubecfg="${1}"
  local namespace="${2}"
  KUBECONFIG="${kubecfg}" oc create sa -n "${namespace}" "${XSITE_SERVICE_ACCOUNT}" || true
  KUBECONFIG="${kubecfg}" oc policy add-role-to-user view -n "${namespace}" -z "${XSITE_SERVICE_ACCOUNT}" || true
}

function get_service_account_token() {
  local kubecfg="${1}"
  local namespace="${2}"
  KUBECONFIG="${kubecfg}" oc create token -n "${namespace}" "${XSITE_SERVICE_ACCOUNT}"
}

function create_token_secret() {
  local kubecfg="${1}"
  local namespace="${2}"
  local secret="${3}"
  local token="${4}"
  KUBECONFIG="${kubecfg}" oc delete secret -n "${namespace}" "${secret}" || true
  KUBECONFIG="${kubecfg}" oc create secret generic -n "${namespace}" "${secret}" --from-literal=token="${token}"
}

function deploy_infinispan_cr_same_cluster() {
    local kubecfg="${1}"
    local namespace="${2}"
    local local_site="${3}"
    local remote_namespace="${4}"
    local remote_site="${5}"
    KUBECONFIG="${kubecfg}" oc apply -f - << EOF
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: infinispan
  namespace: ${namespace}
  annotations:
      infinispan.org/monitoring: 'true'
spec:
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchLabels:
              app: infinispan-pod
              clusterName: infinispan
              infinispan_cr: infinispan
          topologyKey: "kubernetes.io/hostname"
  replicas: ${ISPN_REPLICAS}
  service:
    type: DataGrid
    sites:
      local:
        name: ${local_site}
        expose:
          type: ClusterIP
        maxRelayNodes: 128
      locations:
        - name: ${remote_site}
          clusterName: infinispan
          namespace: ${remote_namespace}
EOF
}

function deploy_infinispan_cr() {
    local kubecfg="${1}"
    local namespace="${2}"
    local local_site="${3}"
    local remote_namespace="${4}"
    local remote_site="${5}"
    local api_url="${6}"
    KUBECONFIG="${kubecfg}" oc apply -f - << EOF
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: infinispan
  namespace: ${namespace}
  annotations:
        infinispan.org/monitoring: 'true'
spec:
  affinity:
      podAntiAffinity:
        preferredDuringSchedulingIgnoredDuringExecution:
        - weight: 100
          podAffinityTerm:
            labelSelector:
              matchLabels:
                app: infinispan-pod
                clusterName: infinispan
                infinispan_cr: infinispan
            topologyKey: "kubernetes.io/hostname"
  replicas: ${ISPN_REPLICAS}
  service:
    type: DataGrid
    sites:
      local:
        name: ${local_site}
        expose:
          type: Route
        maxRelayNodes: 128
        encryption:
          protocol: TLSv1.3
          transportKeyStore:
            secretName: ${XSITE_KS_TLS_SECRET}
            alias: xsite
            filename: keystore.p12
          routerKeyStore:
            secretName: ${XSITE_KS_TLS_SECRET}
            alias: xsite
            filename: keystore.p12
          trustStore:
            secretName: ${XSITE_TS_TLS_SECRET}
            filename: truststore.p12
      locations:
        - name: ${remote_site}
          url: ${api_url}
          secretName: ${XSITE_TOKEN_SECRET}
EOF
}

function deploy_infinispan_cr_without_cross_site() {
    local kubecfg="${1}"
    local namespace="${2}"
    KUBECONFIG="${kubecfg}" oc apply -f - << EOF
apiVersion: infinispan.org/v1
kind: Infinispan
metadata:
  name: infinispan
  namespace: ${namespace}
  annotations:
      infinispan.org/monitoring: 'true'
spec:
  affinity:
    podAntiAffinity:
      preferredDuringSchedulingIgnoredDuringExecution:
      - weight: 100
        podAffinityTerm:
          labelSelector:
            matchLabels:
              app: infinispan-pod
              clusterName: infinispan
              infinispan_cr: infinispan
          topologyKey: "kubernetes.io/hostname"
  replicas: ${ISPN_REPLICAS}
  service:
    type: DataGrid
EOF
}

function deploy_cache_cr() {
  local kubecfg="${1}"
  local namespace="${2}"
  local cache_name="${3}"
  local remote_site="${4}"
  local xsite_mode="${5}"
  local cr_name=$(echo "${cache_name}" | awk '{print tolower($0)}')

  KUBECONFIG="${kubecfg}" oc apply -f - << EOF
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: ${cr_name}
  namespace: ${namespace}
spec:
  clusterName: infinispan
  name: ${cache_name}
  template: |-
    distributedCache:
      mode: "SYNC"
      owners: "2"
      statistics: "true"
      stateTransfer:
        chunkSize: 16
      backups:
        ${remote_site}:
          backup:
            strategy: ${xsite_mode}
            stateTransfer:
              chunkSize: 16
EOF
}

function deploy_cache_cr_without_cross_site() {
  local kubecfg="${1}"
  local namespace="${2}"
  local cache_name="${3}"
  local cr_name=$(echo "${cache_name}" | awk '{print tolower($0)}')

  KUBECONFIG="${kubecfg}" oc apply -f - << EOF
apiVersion: infinispan.org/v2alpha1
kind: Cache
metadata:
  name: ${cr_name}
  namespace: ${namespace}
spec:
  clusterName: infinispan
  name: ${cache_name}
  template: |-
    distributedCache:
      mode: "SYNC"
      owners: "2"
      statistics: "true"
      stateTransfer:
        chunkSize: 16
EOF
}

function deploy_all_caches() {
  local kubecfg="${1}"
  local namespace="${2}"
  local remote_site="${3}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "sessions" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "actionTokens" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "authenticationSessions" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "offlineSessions" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "clientSessions" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "offlineClientSessions" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "loginFailures" "${remote_site}" "${XSITE_MODE}"
  deploy_cache_cr "${kubecfg}" "${namespace}" "work" "${remote_site}" "${XSITE_MODE}"
}

function get_api_url() {
    local kubecfg="${1}"
    KUBECONFIG="${kubecfg}" oc config view -o jsonpath='{.clusters[0].cluster.server}' | sed 's|^http[s]://||g'
}

function create_cross_site_single_cluster() {
  # Use namespace as site's name
  local site1="${NS_1}"
  local site2="${NS_2}"
  # TLS not required for single cluster cross-site
  # Login in cluster
  rosa_oc_login "$KUBECONFIG_1" "${CLUSTER_1}"

  # Check and create the namepsaces if missing
  KUBECONFIG="${KUBECONFIG_1}" oc new-project "${NS_1}" || true
  KUBECONFIG="${KUBECONFIG_1}" oc new-project "${NS_2}" || true

  # Deploy an Infinispan cluster in each of the namespaces.
  deploy_infinispan_cr_same_cluster "${KUBECONFIG_1}" "${NS_1}" "${site1}" "${NS_2}" "${site2}"
  deploy_infinispan_cr_same_cluster "${KUBECONFIG_1}" "${NS_2}" "${site2}" "${NS_1}" "${site2}"

  # Creates caches on site A
  deploy_all_caches "${KUBECONFIG_1}" "${NS_1}" "${site2}"

  # Create caches on site B
  deploy_all_caches "${KUBECONFIG_1}" "${NS_2}" "${site1}"
}

function create_cross_site_multiple_clusters() {
  # Login on both clusters
  rosa_oc_login "${KUBECONFIG_1}" "${CLUSTER_1}"
  rosa_oc_login "${KUBECONFIG_2}" "${CLUSTER_2}"

  # Check and create the namepsaces if missing
  KUBECONFIG="${KUBECONFIG_1}" oc new-project "${NS_1}" || true
  KUBECONFIG="${KUBECONFIG_2}" oc new-project "${NS_2}" || true

  # Create secrets for TLS (Openshift Route)
  create_tls_secrets "${KUBECONFIG_1}" "${NS_1}"
  create_tls_secrets "${KUBECONFIG_2}" "${NS_2}"

  # Create and share access tokens
  create_service_account "${KUBECONFIG_1}" "${NS_1}"
  create_service_account "${KUBECONFIG_2}" "${NS_2}"

  local token1="$(get_service_account_token "${KUBECONFIG_1}" "${NS_1}")"
  local token2="$(get_service_account_token "${KUBECONFIG_2}" "${NS_2}")"

  create_token_secret "${KUBECONFIG_1}" "${NS_1}" "${XSITE_TOKEN_SECRET}" "${token2}"
  create_token_secret "${KUBECONFIG_2}" "${NS_2}" "${XSITE_TOKEN_SECRET}" "${token1}"

  local api_url_1="openshift://$(get_api_url "${KUBECONFIG_1}")"
  local api_url_2="openshift://$(get_api_url "${KUBECONFIG_2}")"

  # Use cluster name as site name
  local site1="${CLUSTER_1}"
  local site2="${CLUSTER_2}"

  deploy_infinispan_cr "${KUBECONFIG_1}" "${NS_1}" "${site1}" "${NS_2}" "${site2}" "${api_url_2}"
  deploy_infinispan_cr "${KUBECONFIG_2}" "${NS_2}" "${site2}" "${NS_1}" "${site1}" "${api_url_1}"

   # Creates caches on site A
   deploy_all_caches "${KUBECONFIG_1}" "${NS_1}" "${site2}"

   # Create caches on site B
   deploy_all_caches "${KUBECONFIG_1}" "${NS_2}" "${site1}"
}

function create_cluster_without_cross_site() {
  # TLS not required for single cluster cross-site
  # Login in cluster
  rosa_oc_login "$KUBECONFIG_1" "${CLUSTER_1}"

  # Check and create the namepsaces if missing
  KUBECONFIG="${KUBECONFIG_1}" oc new-project "${NS_1}" || true

  # Deploy an Infinispan cluster in each of the namespaces.
  deploy_infinispan_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}"

  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "sessions"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "authenticationSessions"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "actionTokens"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "offlineSessions"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "clientSessions"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "offlineClientSessions"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "loginFailures"
  deploy_cache_cr_without_cross_site "${KUBECONFIG_1}" "${NS_1}" "work"
}

########
# MAIN #
########

if [ "${CLUSTER_1}" == "${CLUSTER_2}" ] || [ "${CLUSTER_2}" == "" ]; then
  if [ "$NS_1" == "$NS_2" ] || [ "${NS_2}" == "" ]; then
   create_cluster_without_cross_site
   exit 0
 fi
  [ -z "${NS_2}" ] && error_and_exit 4 "NS_2 is required. NS_2 is the namespace to install Infinispan in the second ROSA cluster"
  create_cross_site_single_cluster
else
  [ -z "${CLUSTER_2}" ] && error_and_exit 2 "CLUSTER_2 is required. CLUSTER_2 is the name of the second ROSA cluster."
  [ -z "${NS_2}" ] && error_and_exit 4 "NS_2 is required. NS_2 is the namespace to install Infinispan in the second ROSA cluster"
  create_cross_site_multiple_clusters
fi
