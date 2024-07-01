#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

KEYCLOAK_MASTER_PASSWORD_SECRET_NAME=${KEYCLOAK_MASTER_PASSWORD_SECRET_NAME:-"keycloak-master-password"}
# Force eu-central-1 region for secrets manager so we all work with the same secret
SECRET_MANAGER_REGION="eu-central-1"

MAIN_PASSWORD=$(aws secretsmanager get-secret-value --region $SECRET_MANAGER_REGION --secret-id $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME --query SecretString --output text --no-cli-pager)

MVN_CMD="./mvnw -B -f provision/rosa-cross-dc/keycloak-benchmark-crossdc-tests/pom.xml clean install -DcrossDCTests \
     -Ddeployment.namespace=${DEPLOYMENT_NAMESPACE} \
     -Dkubernetes.1.context=${KUBERNETES_1_CONTEXT} \
     -Dkubernetes.2.context=${KUBERNETES_2_CONTEXT} \
     -Dmain.password=${MAIN_PASSWORD} \
     -DskipEmbeddedCaches=${SKIP_EMBEDDED_CACHES:-false}"

if [ "${ACTIVE_ACTIVE}" == "true" ]; then
  MVN_CMD+=" -Pactive-active"
fi

echo ${MVN_CMD}
${MVN_CMD}
