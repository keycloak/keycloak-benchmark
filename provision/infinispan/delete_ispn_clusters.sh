#!/bin/bash

set -e
set -x

WD=$(dirname $0)

# Required options set in commons.sh
. ${WD}/commons.sh

function delete_infinispan_cr() {
  local kubecfg="${1}"
  local namespace="${2}"
  local infinispan_cr="${3}"
  KUBECONFIG="${kubecfg}" oc delete infinispans.infinispan.org -n "${namespace}" "${infinispan_cr}"
}

########
# MAIN #
########

if [ "${CLUSTER_1}" == "${CLUSTER_2}" ] || [ "${CLUSTER_2}" == "" ]; then
  rosa_oc_login "${KUBECONFIG_1}" "${CLUSTER_1}"
  delete_infinispan_cr "${KUBECONFIG_1}" "${NS_1}" "infinispan"
  if [ "$NS_1" == "$NS_2" ] || [ "${NS_2}" == "" ]; then
    exit 0
  fi
  [ -z "${NS_2}" ] && error_and_exit 4 "NS_2 is required. NS_2 is the namespace to install Infinispan in the second ROSA cluster"
  delete_infinispan_cr "${KUBECONFIG_1}" "${NS_2}" "infinispan"
else
  [ -z "${CLUSTER_2}" ] && error_and_exit 2 "CLUSTER_2 is required. CLUSTER_2 is the name of the second ROSA cluster."
  [ -z "${NS_2}" ] && error_and_exit 4 "NS_2 is required. NS_2 is the namespace to install Infinispan in the second ROSA cluster"
  rosa_oc_login "${KUBECONFIG_1}" "${CLUSTER_1}"
  rosa_oc_login "${KUBECONFIG_2}" "${CLUSTER_2}"

  delete_infinispan_cr "${KUBECONFIG_1}" "${NS_1}" "infinispan"
  delete_infinispan_cr "${KUBECONFIG_2}" "${NS_2}" "infinispan"
fi
