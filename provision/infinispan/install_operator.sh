#!/bin/bash

INSTALL_NAMESPACE=${INSTALL_NAMESPACE:-"openshift-operators"}
OPERATOR_SOURCE=${OPERATOR_SOURCE:-"community-operators"}
OPERATOR_SOURCE_NS=${OPERATOR_SOURCE_NS:-"openshift-marketplace"}
OPERATOR_CHANNEL=${OPERATOR_CHANNEL:-"2.3.x"}

echo "Installing Infinispan operator."

# Create subscription for Infinispan Operator
oc apply -f - << EOF
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: infinispan
  namespace: ${INSTALL_NAMESPACE}
spec:
  channel: ${OPERATOR_CHANNEL}
  installPlanApproval: Automatic
  name: infinispan
  source: ${OPERATOR_SOURCE}
  sourceNamespace: ${OPERATOR_SOURCE_NS}
EOF
