#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/route53_common.sh

if [ -z "${SUBDOMAIN}" ]; then
  echo "'SUBDOMAIN' env not set, removing all Route 53 records and health checks associated with *.${ROOT_DOMAIN}"
fi

DOMAIN="${SUBDOMAIN}.${ROOT_DOMAIN}"

# Delete health checks
HEALTH_CHECKS=$(aws route53 list-health-checks \
  --query "HealthChecks[?ends_with(HealthCheckConfig.FullyQualifiedDomainName, '${DOMAIN}')]" \
  --output json
)
echo ${HEALTH_CHECKS} | jq -c '.[]' | while read HEALTH_CHECK; do
  HEALTH_CHECK_ID=${HEALTH_CHECK} ${SCRIPT_DIR}/route53_delete_failover_lambda.sh
  aws route53 delete-health-check --health-check-id $(echo ${HEALTH_CHECK} | jq -r .Id)
done

# Delete Hosted Zone records
RECORD_SETS=$(aws route53 list-resource-record-sets \
  --hosted-zone-id ${HOSTED_ZONE_ID} \
  --query "ResourceRecordSets[?ends_with(Name, '${DOMAIN}.')]" \
  --output json
)
echo ${RECORD_SETS} | jq -c '.[]' | while read RECORD_SET; do
  aws route53 change-resource-record-sets \
  --hosted-zone-id ${HOSTED_ZONE_ID} \
  --change-batch '
  {
  	"Comment": "Delete",
  	"Changes": [{
  		"Action": "DELETE",
  		"ResourceRecordSet": '${RECORD_SET}'
  	}]
  }
  '
done
