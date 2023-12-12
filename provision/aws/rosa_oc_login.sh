#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

CLUSTER_NAME=${CLUSTER_NAME:-$(whoami)}
KEYCLOAK_MASTER_PASSWORD_SECRET_NAME=${KEYCLOAK_MASTER_PASSWORD_SECRET_NAME:-"keycloak-master-password"}
# Force eu-central-1 region for secrets manager so we all work with the same secret
SECRET_MANAGER_REGION="eu-central-1"

ADMIN_PASSWORD=$(aws secretsmanager get-secret-value --region $SECRET_MANAGER_REGION --secret-id $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME --query SecretString --output text --no-cli-pager)

API_URL=$(rosa describe cluster -c "$CLUSTER_NAME" -o json | jq -r '.api.url')

CONSOLE_URL=$(rosa describe cluster -c "$CLUSTER_NAME" -o json | jq -r '.console.url')

oc login $API_URL --username cluster-admin --password $ADMIN_PASSWORD --insecure-skip-tls-verify

echo Use ${CONSOLE_URL} to log in using the console
