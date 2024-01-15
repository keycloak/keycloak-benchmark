#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/route53_common.sh

if [ -z "${DOMAIN}" ]; then
  echo "DOMAIN variable must be set"
  exit 1
fi

HEALTH_CHECK_ID=$(aws route53 list-health-checks \
  --query "HealthChecks[?HealthCheckConfig.FullyQualifiedDomainName=='${DOMAIN}'].Id" \
  --output text
)
aws route53 update-health-check \
  --health-check-id ${HEALTH_CHECK_ID} \
  --resource-path "/lb-check"

if [[ "${WAIT}" == 1 ]]; then
  # Wait for health check ALARM to pass before returning
  ALARM_NAME=${HEALTH_CHECK_ID}
  count=0
  until isAlarmOK ${ALARM_NAME} || (( count++ >= 60 )); do
    sleep 10
  done
fi
