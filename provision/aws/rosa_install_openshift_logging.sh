#!/usr/bin/env bash

set -eo pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/rosa_common.sh

echo "Installing openshift operator."

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
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: openshift-logging
  namespace: openshift-logging
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

echo "Installing openshift logging."

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
