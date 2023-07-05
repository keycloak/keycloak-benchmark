#!/bin/bash

set -eo pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

INSTALL_NAMESPACE=${INSTALL_NAMESPACE:-cert-manager-operator}

oc new-project ${INSTALL_NAMESPACE} || true

# Create subscription for Infinispan Operator
oc apply -f - << EOF
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: cert-manager-operator-1
  namespace: ${INSTALL_NAMESPACE}
spec:
  targetNamespaces:
    - cert-manager-operator
  upgradeStrategy: Default
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: cert-manager
  namespace: ${INSTALL_NAMESPACE}
spec:
  channel: stable-v1
  installPlanApproval: Automatic
  name: openshift-cert-manager-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
EOF
