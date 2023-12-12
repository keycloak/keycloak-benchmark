#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

KEYCLOAK_MASTER_PASSWORD_SECRET_NAME=${KEYCLOAK_MASTER_PASSWORD_SECRET_NAME:-"keycloak-master-password"}
# Force eu-central-1 region for secrets manager so we all work with the same secret
SECRET_MANAGER_REGION="eu-central-1"

ISPN_PASSWORD=$(aws secretsmanager get-secret-value --region $SECRET_MANAGER_REGION --secret-id $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME --query SecretString --output text --no-cli-pager)

./mvnw -B -f provision/rosa-cross-dc/keycloak-benchmark-crossdc-tests/pom.xml clean install -DcrossDCTests \
-Dload-balancer.url=$LOAD_BALANCER_URL \
-Dinfinispan.dc1.url=$ISPN_DC1_URL -Dkeycloak.dc1.url=$KEYCLOAK_DC1_URL \
-Dinfinispan.dc2.url=$ISPN_DC2_URL -Dkeycloak.dc2.url=$KEYCLOAK_DC2_URL \
-Dinfinispan.password=$ISPN_PASSWORD
