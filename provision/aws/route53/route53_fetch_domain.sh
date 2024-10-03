#!/bin/bash

# Domain name (include trailing dot)
DOMAIN=$1

# Fetch Hosted Zone ID
hosted_zone_id=$(aws route53 list-hosted-zones \
    --query "HostedZones[?Name=='$DOMAIN'].Id" \
    --output text)

if [ -z "$hosted_zone_id" ]; then
    echo "Hosted Zone ID for $DOMAIN not found."
    exit 1
fi

# Remove '/hostedzone/' prefix from ID if present
hosted_zone_id=${hosted_zone_id##*/}

# List Resource Record Sets
aws route53 list-resource-record-sets --hosted-zone-id "$hosted_zone_id" --query 'ResourceRecordSets[].Name' --output json | jq -r '.[]' | sed 's/\.$//' | sed 's/^[^.]*\.//' | grep -v '^keycloak-benchmark\.com$' | sort | uniq -c | sort -nr | head -1 | awk '{print $2}'

