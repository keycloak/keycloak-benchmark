#!/usr/bin/env bash

function requiredEnv() {
  for ENV in $@; do
      if [ -z "${!ENV}" ]; then
        echo "${ENV} variable must be set"
        exit 1
      fi
  done
}

# Wait for k8s resource to exist. See: https://github.com/kubernetes/kubernetes/issues/83242
function waitFor() {
  xtrace=$(set +o|grep xtrace); set +x
  local ns=${1?namespace is required}; shift
  local type=${1?type is required}; shift

  echo "Waiting for $type $*"
  until oc -n "$ns" get "$type" "$@" -o=jsonpath='{.items[0].metadata.name}' >/dev/null 2>&1; do
    echo "Waiting for $type $*"
    sleep 1
  done
  eval "$xtrace"
}

# Wait until OLM has installed an operator subscription successfully. Creating
# a Subscription only starts an asynchronous installation; consumers of its
# CRDs and admission webhooks must not proceed until the installed CSV is
# ready.
function waitForOperatorSubscription() {
  local ns=${1?namespace is required}; shift
  local subscription=${1?subscription is required}; shift
  local timeout_seconds=${1:-600}
  local deadline=$((SECONDS + timeout_seconds))
  local installed_csv=""
  local phase=""

  echo "Waiting for operator subscription ${subscription} in ${ns}"
  while (( SECONDS < deadline )); do
    installed_csv=$(oc -n "${ns}" get subscription "${subscription}" -o jsonpath='{.status.installedCSV}' 2>/dev/null || true)
    if [[ -n "${installed_csv}" ]]; then
      phase=$(oc -n "${ns}" get csv "${installed_csv}" -o jsonpath='{.status.phase}' 2>/dev/null || true)
      if [[ "${phase}" == "Succeeded" ]]; then
        echo "Operator CSV ${installed_csv} is ready."
        return 0
      fi
    fi
    sleep 5
  done

  echo "Timed out after ${timeout_seconds}s waiting for operator subscription ${subscription} in ${ns}." >&2
  oc -n "${ns}" get subscription "${subscription}" -o yaml >&2 || true
  if [[ -n "${installed_csv}" ]]; then
    oc -n "${ns}" get csv "${installed_csv}" -o yaml >&2 || true
  fi
  return 1
}

# A webhook Service can exist before its backing operator pod is ready. Wait
# for Kubernetes to publish at least one endpoint before creating resources
# that invoke the webhook.
function waitForServiceEndpoint() {
  local ns=${1?namespace is required}; shift
  local service=${1?service is required}; shift
  local timeout_seconds=${1:-300}
  local deadline=$((SECONDS + timeout_seconds))
  local addresses=""

  echo "Waiting for service ${service} in ${ns} to have a ready endpoint"
  while (( SECONDS < deadline )); do
    addresses=$(oc -n "${ns}" get endpoints "${service}" -o jsonpath='{.subsets[*].addresses[*].ip}' 2>/dev/null || true)
    if [[ -n "${addresses}" ]]; then
      echo "Service ${service} has a ready endpoint."
      return 0
    fi
    sleep 5
  done

  echo "Timed out after ${timeout_seconds}s waiting for service ${service} in ${ns} to have a ready endpoint." >&2
  oc -n "${ns}" get service "${service}" -o yaml >&2 || true
  oc -n "${ns}" get endpoints "${service}" -o yaml >&2 || true
  return 1
}
