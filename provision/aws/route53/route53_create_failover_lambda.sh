#!/bin/bash
set -e -o pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/route53_common.sh

if [ -z "${HEALTH_CHECK_ID}" ]; then
  echo "HEALTH_CHECK_ID variable must be set"
  exit 1
fi

ALARM_NAME=${HEALTH_CHECK_ID}
TOPIC_NAME=${HEALTH_CHECK_ID}
FUNCTION_NAME=${HEALTH_CHECK_ID}
ROUTE53_REGION="us-east-1"

export AWS_REGION=${ROUTE53_REGION}

TOPIC_ARN=$(aws sns create-topic --name ${TOPIC_NAME} \
  --query "TopicArn" \
  --tags "Key=HealthCheckId,Value=${HEALTH_CHECK_ID}" \
  --output text
)

# Create CloudWatch alarm
aws cloudwatch put-metric-alarm \
  --alarm-actions ${TOPIC_ARN} \
  --actions-enabled \
  --alarm-name ${ALARM_NAME} \
  --dimensions "Name=HealthCheckId,Value=${HEALTH_CHECK_ID}" \
  --comparison-operator LessThanThreshold \
  --evaluation-periods 1 \
  --metric-name HealthCheckStatus \
  --namespace AWS/Route53 \
  --period 60 \
  --statistic Minimum \
  --threshold 1.0 \

# Create the Role used to execute the Lambda
ROLE_ARN=$(aws iam create-role \
  --role-name ${FUNCTION_NAME} \
  --assume-role-policy-document \
  '{
    "Version": "2012-10-17",
    "Statement": [
      {
        "Effect": "Allow",
        "Principal": {
          "Service": "lambda.amazonaws.com"
        },
        "Action": "sts:AssumeRole"
      }
    ]
  }' \
  --query 'Role.Arn' \
  --output text
)

# Create a policy with the permissions required by lambda.py
POLICY_ARN=$(aws iam create-policy \
  --policy-name ${FUNCTION_NAME} \
  --policy-document \
  '{
      "Version": "2012-10-17",
      "Statement": [
          {
              "Effect": "Allow",
              "Action": [
                  "route53:UpdateHealthCheck"
              ],
              "Resource": "*"
          }
      ]
  }' \
  --query 'Policy.Arn' \
  --output text
)

# Attach the custom policy to the Lambda role
aws iam attach-role-policy \
  --role-name ${FUNCTION_NAME} \
  --policy-arn ${POLICY_ARN}

# Attach the AWSLambdaBasicExecutionRole policy so that the Lambda logs can be written to CloudWatch
aws iam attach-role-policy \
  --role-name ${FUNCTION_NAME} \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

# Wait for Role to be updated in order to avoid 'The role defined for the function cannot be assumed by Lambda' errors
sleep 10

# Create Lambda distribution
LAMBDA_ZIP=/tmp/${AURORA_GLOBAL_CLUSTER}-function.zip
zip -FS --junk-paths ${LAMBDA_ZIP} ${SCRIPT_DIR}/lambda.py

# Create Lambda function
FUNCTION_ARN=$(aws lambda create-function \
  --function-name ${FUNCTION_NAME} \
  --zip-file fileb://${LAMBDA_ZIP} \
  --handler lambda.handler \
  --runtime python3.11 \
  --role ${ROLE_ARN} \
  --query 'FunctionArn' \
  --output text
)

aws lambda add-permission \
  --function-name ${FUNCTION_NAME} \
  --statement-id function-with-sns \
  --action 'lambda:InvokeFunction' \
  --principal 'sns.amazonaws.com' \
  --source-arn ${TOPIC_ARN}

# Invoke the Lambda when SNS message received
aws sns subscribe --protocol lambda \
  --topic-arn ${TOPIC_ARN} \
  --notification-endpoint ${FUNCTION_ARN}
