#!/bin/bash

set -eo pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

# Wait for k8s resource to exist. See: https://github.com/kubernetes/kubernetes/issues/83242
waitFor() {
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

oc apply -f - << EOF
apiVersion: v1
kind: Namespace
metadata:
  name: openshift-operators-redhat
---
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: openshift-operators-redhat
  namespace: openshift-operators-redhat
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: elasticsearch-operator
  namespace: openshift-operators-redhat
spec:
  channel: stable
  installPlanApproval: Automatic
  name: elasticsearch-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: cluster-logging
  namespace: openshift-logging
spec:
  channel: stable
  installPlanApproval: Automatic
  name: cluster-logging
  source: redhat-operators
  sourceNamespace: openshift-marketplace
EOF

waitFor default crd clusterloggings.logging.openshift.io
oc wait --for condition=established --timeout=60s crd/clusterloggings.logging.openshift.io

oc apply -f - << EOF
apiVersion: logging.openshift.io/v1
kind: ClusterLogging
metadata:
  name: instance
  namespace: openshift-logging
spec:
  collection:
    logs:
      fluentd: {}
      type: fluentd
  logStore:
    elasticsearch:
      nodeCount: 1
      proxy:
        resources:
          limits:
            memory: 256Mi
          requests:
            memory: 256Mi
      resources:
        limits:
          memory: 4Gi
        requests:
          memory: 4Gi
      storage:
        size: 200G
    retentionPolicy:
      application:
        maxAge: 1d
      audit:
        maxAge: 7d
      infra:
        maxAge: 7d
    type: elasticsearch
  managementState: Managed
  visualization:
    kibana:
      replicas: 1
    type: kibana
EOF

# install the console plugin
oc patch console.operator cluster -n openshift-storage --type json -p '[{"op": "add", "path": "/spec/plugins", "value": ["logging-view-plugin"]}]'
