#!/bin/bash

set -eo pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

INSTALL_NAMESPACE=${INSTALL_NAMESPACE:-openshift-operators}

oc apply -f - << EOF
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: cryostat-operator
  namespace: ${INSTALL_NAMESPACE}
spec:
  channel: stable
  installPlanApproval: Automatic
  name: cryostat-operator
  source: redhat-operators
  sourceNamespace: openshift-marketplace
EOF
