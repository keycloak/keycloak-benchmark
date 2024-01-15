#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/route53_common.sh

if [ -z "${HEALTH_CHECK_ID}" ]; then
  if [ -z "${DOMAIN}" ]; then
    echo "HEALTH_CHECK_ID or DOMAIN variable must be set"
    exit 1
  fi
  HEALTH_CHECK_ID=$(aws route53 list-health-checks \
    --query "HealthChecks[?HealthCheckConfig.FullyQualifiedDomainName=='${DOMAIN}'].Id" \
    --output text
  )
fi

ALARM_NAME=${HEALTH_CHECK_ID}
TOPIC_NAME=${HEALTH_CHECK_ID}
FUNCTION_NAME=${HEALTH_CHECK_ID}
ROUTE53_REGION="us-east-1"

export AWS_REGION=${ROUTE53_REGION}

# Remove Lambda
aws lambda delete-function --function-name ${FUNCTION_NAME} || true

# Remove Roles and Policies created for Lambda
POLICY_ARN=$(aws iam list-policies \
  --scope Local \
  --query "Policies[?PolicyName=='${FUNCTION_NAME}'].Arn" \
  --output text
)
if [ -n "${POLICY_ARN}" ]; then
  aws iam detach-role-policy \
    --role-name ${FUNCTION_NAME} \
    --policy-arn ${POLICY_ARN}

  aws iam detach-role-policy \
    --role-name ${FUNCTION_NAME} \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

  aws iam delete-policy --policy-arn ${POLICY_ARN}
  aws iam delete-role --role-name ${FUNCTION_NAME}
fi

# Remove CloudWatch alarm
ALARM_NAMES=$(aws cloudwatch describe-alarms \
  --query "MetricAlarms[?Dimensions[0].Name=='HealthCheckId' && Dimensions[0].Value=='${HEALTH_CHECK_ID}'].AlarmName" \
  --output text
)
aws cloudwatch delete-alarms --alarm-names ${ALARM_NAMES}

# Remove SNS topic and all subscriptions
TOPIC_ARNS=$(aws sns list-topics \
  --query "Topics[].TopicArn" \
  --output text
)
for TOPIC_ARN in ${TOPIC_ARNS}; do
  TAG=$(aws sns list-tags-for-resource \
    --resource-arn ${TOPIC_ARN} \
    --query "Tags[?Key=='HealthCheckId'].Value" \
    --output text
  )
  if [ -n "${TAG}" ]; then
    aws sns delete-topic --topic-arn ${TOPIC_ARN}
    break
  fi
done
