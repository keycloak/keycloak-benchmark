#!/usr/bin/env bash

set -eo pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

INSTALL_NAMESPACE=${INSTALL_NAMESPACE:-openshift-operators}
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" &>/dev/null && pwd)
source "${SCRIPT_DIR}/rosa_common.sh"

echo "Installing tempo operator."

oc apply -f - << EOF
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: tempo-product
  namespace: ${INSTALL_NAMESPACE}
spec:
  channel: stable
  installPlanApproval: Automatic
  name: tempo-product
  source: redhat-operators
  sourceNamespace: openshift-marketplace
EOF

waitForOperatorSubscription "${INSTALL_NAMESPACE}" tempo-product
waitForServiceEndpoint "${INSTALL_NAMESPACE}" tempo-operator-controller-service
