#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

CLUSTER_NAME=${CLUSTER_NAME:-$(whoami)}
if [ -z "$CLUSTER_NAME" ]; then echo "Variable CLUSTER_NAME needs to be set."; exit 1; fi


KEYCLOAK_MASTER_PASSWORD_SECRET_NAME=${KEYCLOAK_MASTER_PASSWORD_SECRET_NAME:-"keycloak-master-password"}
# Force eu-central-1 region for secrets manager so we all work with the same secret
SECRET_MANAGER_REGION="eu-central-1"
ADMIN_PASSWORD=$(aws secretsmanager get-secret-value --region $SECRET_MANAGER_REGION --secret-id $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME --query SecretString --output text --no-cli-pager)

if [ -z "$ADMIN_PASSWORD" ]; then
  echo "Keycloak master password was not found in the secretmanager. Recreate it using ./aws_rotate_keycloak_master_password.sh script."
  exit 1
fi
CLUSTER_DESCRIPTION=$(rosa describe cluster --cluster "$CLUSTER_NAME" --output json)

echo $CLUSTER_DESCRIPTION

CLUSTER_ID=$(echo "$CLUSTER_DESCRIPTION" | jq -r '.id')
REGION=$(echo "$CLUSTER_DESCRIPTION" | jq -r '.region.id')
API_URL=$(echo "$CLUSTER_DESCRIPTION" | jq -r '.api.url')

echo "CLUSTER_NAME: $CLUSTER_NAME"
echo "CLUSTER_ID: $CLUSTER_ID"
echo "REGION: $REGION"
echo "API_URL: $API_URL"

echo "Recreating the admin user."
rosa delete admin --cluster "${CLUSTER_NAME}" --yes || true
rosa create admin --cluster "${CLUSTER_NAME}" --password "${ADMIN_PASSWORD}"
OC_LOGIN_CMD="oc login ${API_URL} --username cluster-admin --password ${ADMIN_PASSWORD} --insecure-skip-tls-verify"

echo "New admin user created."
echo
echo "     $OC_LOGIN_CMD"
echo
echo "Waiting for 'oc login' to succeed."

TIMEOUT=$(($(date +%s) + 3600))
while true ; do
  if (${OC_LOGIN_CMD}); then
    break
  fi
  if (( TIMEOUT < $(date +%s))); then
    echo "Timeout exceeded"
    exit 1
  fi
  date -uIs
  sleep 10
done
