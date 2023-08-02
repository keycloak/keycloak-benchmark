#!/bin/bash
set -e

if [ -f ./.env ]; then
  source ./.env
fi

export AWS_PAGER=""
export ROOT_DOMAIN=${ROOT_DOMAIN:-"keycloak-benchmark.com"}

# Retrieve the Hosted Zone associated with our root domain
export HOSTED_ZONE_ID=$(aws route53 list-hosted-zones \
  --query "HostedZones[?Name=='${ROOT_DOMAIN}.'].Id" \
  --output text \
  | sed 's:.*/::'
)
