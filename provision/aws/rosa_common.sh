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
