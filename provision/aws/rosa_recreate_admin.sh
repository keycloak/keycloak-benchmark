#!/bin/bash
set -e

source ./.env

if [ -z "$CLUSTER_NAME" ]; then echo "Variable CLUSTER_NAME needs to be set."; exit 1; fi

if [ -z "$ADMIN_PASSWORD" ]; then ADMIN_PASSWORD_PARAM=""; else ADMIN_PASSWORD_PARAM="--password $ADMIN_PASSWORD"; fi

CLUSTER_DESCRIPTION=$(rosa describe cluster --cluster "$CLUSTER_NAME")

CLUSTER_ID=$(echo "$CLUSTER_DESCRIPTION" | grep -oPm1 "^ID:\s*\K\w+")
REGION=$(echo "$CLUSTER_DESCRIPTION" | grep -oPm1 "^Region:\s*\K[^\s]+")

echo "CLUSTER_NAME: $CLUSTER_NAME"
echo "CLUSTER_ID: $CLUSTER_ID"
echo "REGION: $REGION"

echo "Recreating the admin user."
rosa delete admin --cluster "${CLUSTER_NAME}" --yes || true
OC_LOGIN_CMD=$(rosa create admin --cluster "${CLUSTER_NAME}" $ADMIN_PASSWORD_PARAM | grep -o -m 1 "oc login.*")

echo "New admin user created."
echo
echo "     $OC_LOGIN_CMD"
echo
ADMIN_PASSWORD=$(echo "$OC_LOGIN_CMD" | grep -oP "\-\-password\s+\K[^\s]+")

echo "Recreating AWS secret"
aws secretsmanager delete-secret --region $REGION --secret-id "${CLUSTER_ID}-cluster-admin" --no-cli-pager --force-delete-without-recovery || true
echo "Waiting a bit for AWS secret deletion"
sleep 1m

aws secretsmanager create-secret --region $REGION --name "${CLUSTER_ID}-cluster-admin" --secret-string "$ADMIN_PASSWORD" --no-cli-pager
#aws secretsmanager get-secret-value --region $REGION --secret-id "${CLUSTER_ID}-cluster-admin" --output json --no-cli-pager | jq .SecretString

echo "Waiting a bit before attempting 'oc login'"
sleep 1m

echo "Waiting for 'oc login' to succeed."
while ! $OC_LOGIN_CMD; do date -uIs; sleep 10s; done
