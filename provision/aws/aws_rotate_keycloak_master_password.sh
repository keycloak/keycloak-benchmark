#!/usr/bin/env bash
set -e

source ./.env

KEYCLOAK_MASTER_PASSWORD=${KEYCLOAK_MASTER_PASSWORD:-$(aws secretsmanager get-random-password --password-length 32 --exclude-punctuation --no-cli-pager --output text)}
KEYCLOAK_MASTER_PASSWORD_SECRET_NAME=${KEYCLOAK_MASTER_PASSWORD_SECRET_NAME:-"keycloak-master-password"}

# Force eu-central-1 region for secrets manager so we all work with the same secret
REGION="eu-central-1"

mkdir -p "logs"
if aws secretsmanager describe-secret --region $REGION --secret-id "$KEYCLOAK_MASTER_PASSWORD_SECRET_NAME" --no-cli-pager > "logs/$(date -uIs)_describe-secret.log"; then
  echo "Secret $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME already exists. Updating..."
  aws secretsmanager update-secret --region $REGION --secret-id "$KEYCLOAK_MASTER_PASSWORD_SECRET_NAME" --secret-string "$KEYCLOAK_MASTER_PASSWORD" --no-cli-pager > "logs/$(date -uIs)_update-secret.log"
else
  echo "Secret $KEYCLOAK_MASTER_PASSWORD_SECRET_NAME does not exist. Creating..."
  aws secretsmanager create-secret --region $REGION --name "$KEYCLOAK_MASTER_PASSWORD_SECRET_NAME" --secret-string "$KEYCLOAK_MASTER_PASSWORD" --no-cli-pager > "logs/$(date -uIs)_create-secret.log"
fi

echo "Secret created/rotated successfully. Please add this password to Bitwarden entry 'Keycloak Master Password'."
echo 'Execute "aws secretsmanager get-secret-value --region '"$REGION"' --secret-id '"$KEYCLOAK_MASTER_PASSWORD_SECRET_NAME"' --query SecretString --output text --no-cli-pager" to get the password.'


