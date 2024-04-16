#!/bin/bash -e
#
# Script for installing Keycloak Operator via the Operator Lifecycle Manager.
#
# Assuming OLM framework is installed, this script will create an OperatorGroup and a Subscription resources
# based on the supplied parameters.
#
# INSTALL_NAMESPACE - Target namespace for the installation. Required.
# CATALOG_SOURCE_NAMESPACE - Namespace of the catalog source, e.g. `openshift-marketplace`. Required.
# CATALOG_SOURCE - Name of the catalog source, e.g. `community-operators`. Required.
# PRODUCT - Name of the product, e.g. `keycloak-operator`. Required.
# CHANNEL - Update channel for the selected product. Optional. If not set the default channel for the product will be used.
# CSV - Cluster Service Version of the product. Optional.
#       If CSV is not set and parameter VERSION is set then CSV will be set as "${PRODUCT}.v${VERSION}".
#       If neither CSV nor VERSION are set then the default CSV for the selected channel will be used.
#
# Afterwards the script will check for an install plan created by the subscription 
# and if it matches the requested version it will automatically approve the plan.
# 
# Note that upgrading an existing intallation across multiple versions is NOT SUPPORTED 
# because OLM will sequentially create intall plans for all intermediate versions
# whereas this script will only specifically approve the requested version.
#
# Also note that OLM doesn't support downgrades. In these cases it is necessary 
# to install from scratch.
#

function requireVariableToBeSet { if [ -z "${!1}" ]; then echo "ERROR: Variable $1 is not set." >&2; exit 1; fi }

requireVariableToBeSet INSTALL_NAMESPACE
requireVariableToBeSet CATALOG_SOURCE_NAMESPACE
requireVariableToBeSet CATALOG_SOURCE
requireVariableToBeSet PRODUCT

export INSTALL_NAMESPACE
export CATALOG_SOURCE_NAMESPACE
export CATALOG_SOURCE
export PRODUCT

echo "Looking up package manifest for product \"$PRODUCT\" in catalog \"$CATALOG_SOURCE\", namespace \"$CATALOG_SOURCE_NAMESPACE\"."
packageManifest=$(kubectl -n "$CATALOG_SOURCE_NAMESPACE" get packagemanifests --field-selector=metadata.name=$PRODUCT -ojson | jq -r ".items[] | select (.status.catalogSource==\"$CATALOG_SOURCE\")")
if [ -z "$packageManifest" ]; then echo "ERROR: Package manifest not found."; exit 1; fi

if [ -z "$CHANNEL" ]; then
  echo "Parameter CHANNEL not provided. Looking up the default channel."
  CHANNEL=$(echo "$packageManifest" | jq -r .status.defaultChannel)
  if [ -z "$CHANNEL" ]; then echo "ERROR: Default channel not found."; exit 1; fi
  echo "Default channel is: \"$CHANNEL\""
fi
export CHANNEL

if [ -z "$CSV" ]; then
  if [ ! -z "$VERSION" ]; then 
    echo "Parameter CSV not provided. Setting based on PRODUCT and VERSION: \"${PRODUCT}.v${VERSION}\""
    CSV="${PRODUCT}.v${VERSION}"
  else
    echo "Parameter CSV or VERSION not provided. Looking up current CSV for channel \"$CHANNEL\"."
    CSV=$(echo "$packageManifest" | jq -r ".status.channels[] | select(.name==\"${CHANNEL}\") .currentCSV" )
    if [ -z "$CSV" ]; then echo "Error looking up current CSV."; exit 1; fi
    echo "Current CSV for channel \"$CHANNEL\" is: \"$CSV\""
  fi
fi
export CSV

export OPERATOR_GROUP_NAME=${PRODUCT}

og=$(cat olm/templates/operatorgroup.yaml | envsubst)
sub=$(cat olm/templates/subscription.yaml | envsubst)
echo
echo "${og}"
echo
echo "${sub}"
echo

echo "Checking whether subscription with the requested CSV already exists."
if kubectl -n $INSTALL_NAMESPACE wait subscriptions/$PRODUCT --for=jsonpath='.status.installedCSV'=${CSV} --timeout=10s; then
  echo "Subscription with the requested CSV already exists. Skipping."
else
  echo "Subscription with the requested CSV not found. Applying resources."
  echo "${og}"  | kubectl apply -f -
  echo "${sub}" | kubectl apply -f -

  echo "Checking for pending install plans."
  if kubectl -n $INSTALL_NAMESPACE wait subscriptions/$PRODUCT --for=condition=InstallPlanPending --timeout=60s; then
    installPlanName=$(kubectl -n $INSTALL_NAMESPACE get subscriptions/$PRODUCT -ojson | jq -r .status.installPlanRef.name)
    installPlanCSV=$(kubectl -n $INSTALL_NAMESPACE get ip/${installPlanName} -ojson | jq -r '.spec.clusterServiceVersionNames[0]')
    echo "Subscription references install plan \"${installPlanName}\" with CSV \"${installPlanCSV}\"."
    if [[ "$installPlanCSV" == "$CSV" ]]; then
      echo "Install plan CSV matches the requested version. Approving."
      kubectl -n $INSTALL_NAMESPACE patch ip/${installPlanName} --patch '{"spec":{"approved":true}}' --type=merge
    else
      echo "ERROR: CSV doesn't match the requested CSV: \"$CSV\". Install plan NOT APPROVED." 
      exit 2
    fi
    echo "Validating installed CSV."
    kubectl -n $INSTALL_NAMESPACE wait subscriptions/$PRODUCT --for=jsonpath='.status.installedCSV'=${CSV} --timeout=60s
  else
    echo "ERROR: No pending install plans were found." 
    echo "Subscription status:" 
    kubectl -n $INSTALL_NAMESPACE get subscriptions/$PRODUCT -ojson | jq .status.conditions
    exit 1
  fi
fi

if [[ "$KC_WORKAROUND_28638" == "true" ]]; then
  echo "Applying post-install workaround for issue #28638 https://github.com/keycloak/keycloak/issues/28638"

  echo "  looking up keycloak-operator-role for \"$CSV\""
  attempts=30
  for a in $(seq $attempts); do
    keycloakOperatorRole=$(kubectl -n $INSTALL_NAMESPACE get role -l olm.owner=$CSV -ojson | jq -r '.items[0].metadata.name')
    if [[ $keycloakOperatorRole == ${PRODUCT}* ]]; then
      echo "  found: $keycloakOperatorRole"
      break;
    elif [ $a -ge $attempts ]; then
      echo "  ERROR: Role not found after $attempts attempts."
      exit 3
    fi
    sleep 1
  done
  echo "  adding configmaps permissions to the role"
  kubectl -n $INSTALL_NAMESPACE patch role $keycloakOperatorRole --type json -p '[{"op":"add","path":"/rules/-","value":{"apiGroups":[""],"resources":["configmaps"],"verbs":["get","list","watch"]}}]'

  echo "  also adding configmaps permissions to the role definition in CSV \"$CSV\""
  kubectl -n $INSTALL_NAMESPACE patch csv $CSV --type json -p '[{"op":"add","path":"/spec/install/spec/permissions/0/rules/-","value":{"apiGroups":[""],"resources":["configmaps"],"verbs":["get","list","watch"]}}]'

  echo "  looking up operator pod"
  operatorPod=$(kubectl -n $INSTALL_NAMESPACE get pods -oname | grep $PRODUCT | head -1)
  echo "  found: $operatorPod, rebooting"
  kubectl -n $INSTALL_NAMESPACE delete $operatorPod

  echo "Workaround applied."
fi
