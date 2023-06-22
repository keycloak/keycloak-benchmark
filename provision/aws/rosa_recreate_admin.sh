#!/bin/bash
set -e

if [ -f ./.env ]; then
  source ./.env
fi

CLUSTER_NAME=${CLUSTER_NAME:-$(whoami)}
if [ -z "$CLUSTER_NAME" ]; then echo "Variable CLUSTER_NAME needs to be set."; exit 1; fi

KEYCLOAK_MASTER_PASSWORD_SECRET_NAME=${KEYCLOAK_MASTER_PASSWORD_SECRET_NAME:-"keycloak-master-password"}
# Force eu-central-1 region for secrets manager so we all work with the same secret
SECRET_MANAGER_REGION="eu-central-1"
ADMIN_PASSWORD=${ADMIN_PASSWORD:-$(aws secretsmanager get-secret-value --region $SECRET_MANAGER_REGION --secret-id $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME --query SecretString --output text --no-cli-pager)}

if [ -z "$ADMIN_PASSWORD" ]; then
  ./aws_rotate_keycloak_master_password.sh
  ADMIN_PASSWORD=$(aws secretsmanager get-secret-value --region $SECRET_MANAGER_REGION --secret-id $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME --query SecretString --output text --no-cli-pager)
fi

CLUSTER_DESCRIPTION=$(rosa describe cluster --cluster "$CLUSTER_NAME")

CLUSTER_ID=$(echo "$CLUSTER_DESCRIPTION" | grep -oPm1 "^ID:\s*\K\w+")
REGION=$(echo "$CLUSTER_DESCRIPTION" | grep -oPm1 "^Region:\s*\K[^\s]+")

echo "CLUSTER_NAME: $CLUSTER_NAME"
echo "CLUSTER_ID: $CLUSTER_ID"
echo "REGION: $REGION"

echo "Recreating the admin user."
rosa delete admin --cluster "${CLUSTER_NAME}" --yes || true
OC_LOGIN_CMD=$(rosa create admin --cluster "${CLUSTER_NAME}" --password "$ADMIN_PASSWORD" | grep -o -m 1 "oc login.*")
OC_LOGIN_CMD="$OC_LOGIN_CMD --insecure-skip-tls-verify"

echo "New admin user created."
echo
echo "     $OC_LOGIN_CMD"
echo

echo "Waiting for 'oc login' to succeed."
for i in {1..60}; do $OC_LOGIN_CMD && break || date -uIs && sleep 10; done
