#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
source ${SCRIPT_DIR}/aurora_common.sh
source ${SCRIPT_DIR}/aurora_global_common.sh
source ${SCRIPT_DIR}/../route53/route53_common.sh
unset AURORA_SECURITY_GROUP_NAME AURORA_SUBNET_GROUP_NAME

if [ -z "${AURORA_GLOBAL_REGIONS}" ]; then
  echo "AURORA_GLOBAL_REGIONS variable must be set"
  exit 1
fi

AURORA_GLOBAL_CLUSTER=${AURORA_GLOBAL_CLUSTER:-"keycloak-global"}
GLOBAL_REGIONS=(${AURORA_GLOBAL_REGIONS})
PRIMARY_REGION=${GLOBAL_REGIONS[0]}

EXISTING_INSTANCE=$(aws rds describe-global-clusters \
  --region ${PRIMARY_REGION} \
  --query "GlobalClusters[?GlobalClusterIdentifier=='${AURORA_GLOBAL_CLUSTER}'].GlobalClusterIdentifier" \
  --output text
)
if [ -n "${EXISTING_INSTANCE}" ]; then
  echo "Aurora Global instance '${EXISTING_INSTANCE}' already exists in the '${AWS_REGION}' region"
  exit 0
fi

aws rds create-global-cluster --region ${PRIMARY_REGION}  \
  --global-cluster-identifier ${AURORA_GLOBAL_CLUSTER} \
  --database-name keycloak \
  --engine ${AURORA_ENGINE} \
  --engine-version ${AURORA_ENGINE_VERSION}

export AURORA_GLOBAL_CLUSTER_IDENTIFIER="--global-cluster-identifier ${AURORA_GLOBAL_CLUSTER}"
export AURORA_DATABASE_NAME=""
for (( i = 0 ; i < ${#GLOBAL_REGIONS[@]} ; i++ )) ; do
  REGION=${GLOBAL_REGIONS[i]}
  export AURORA_CLUSTER=${AURORA_GLOBAL_CLUSTER}-${REGION}
  # Aurora Global DBs must use one of the memory optimized classes
  export AURORA_INSTANCE_CLASS="db.r5.large"
  export AURORA_REGION=${REGION}
  export AURORA_VPC_CIDR=$(globalAuroraVpcCidr $i)
  export AURORA_SUBNET_A_CIDR=$(globalAuroraSubnetA $i)
  export AURORA_SUBNET_B_CIDR=$(globalAuroraSubnetB $i)

  if [ "${REGION}" != "${PRIMARY_REGION}" ]; then
    export AURORA_GLOBAL_CLUSTER_BACKUP=true
  fi

  ${SCRIPT_DIR}/aurora_create.sh
done

# Create Route53 entry with the primary writer endpoint as URL
PRIMARY_ENDPOINT=$(aws rds describe-db-cluster-endpoints \
  --db-cluster-identifier ${AURORA_GLOBAL_CLUSTER}-${PRIMARY_REGION} \
  --filters 'Name=db-cluster-endpoint-type,Values=WRITER' \
  --query 'DBClusterEndpoints[].Endpoint' \
  --region ${PRIMARY_REGION} \
  --output text
)

CHANGE_ID=$(aws route53 change-resource-record-sets \
  --hosted-zone-id ${HOSTED_ZONE_ID} \
  --query "ChangeInfo.Id" \
  --output text \
  --change-batch \
  '{
    "Comment": "Creating Record Set for '${DOMAIN}'",
    "Changes": [{
      "Action": "UPSERT",
      "ResourceRecordSet": {
        "Name": "'${AURORA_GLOBAL_CLUSTER}.aurora-global.${ROOT_DOMAIN}'",
        "Type": "CNAME",
        "TTL": 60,
        "ResourceRecords": [{
          "Value": "'${PRIMARY_ENDPOINT}'"
        }]
      }
    }]
  }'
)
aws route53 wait resource-record-sets-changed --id ${CHANGE_ID}

# Create the Role used to execute the Lambda
ROLE_ARN=$(aws iam create-role \
  --role-name ${AURORA_GLOBAL_CLUSTER} \
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
  --policy-name ${AURORA_GLOBAL_CLUSTER} \
  --policy-document \
  '{
      "Version": "2012-10-17",
      "Statement": [
          {
              "Effect": "Allow",
              "Action": [
                  "rds:DescribeGlobalClusters",
                  "rds:DescribeDBClusterEndpoints",
                  "route53:ChangeResourceRecordSets",
                  "route53:ListHostedZones"
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
  --role-name ${AURORA_GLOBAL_CLUSTER} \
  --policy-arn ${POLICY_ARN}

# Attach the AWSLambdaBasicExecutionRole policy so that the Lambda logs can be written to CloudWatch
aws iam attach-role-policy \
  --role-name ${AURORA_GLOBAL_CLUSTER} \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

# Wait for Role to be updated in order to avoid 'The role defined for the function cannot be assumed by Lambda' errors
sleep 10

# Create Lambda distribution
LAMBDA_ZIP=/tmp/${AURORA_GLOBAL_CLUSTER}-function.zip
zip -FS --junk-paths ${LAMBDA_ZIP} ${SCRIPT_DIR}/lambda.py

# Deploy the Lambda to all Aurora regions
for REGION in ${AURORA_GLOBAL_REGIONS}; do
  export AWS_REGION=${REGION}
  # Create Lambda function
  FUNCTION_NAME=${AURORA_GLOBAL_CLUSTER}-failover
  FUNCTION_ARN=$(aws lambda create-function \
    --function-name ${FUNCTION_NAME} \
    --zip-file fileb://${LAMBDA_ZIP} \
    --handler lambda.handler \
    --runtime python3.11 \
    --role ${ROLE_ARN} \
    --query 'FunctionArn' \
    --output text
  )
  # Create Event rule
  # RDS-EVENT-0185 Global switchover to DB cluster name in Region name finished.
  # RDS-EVENT-0238 Global failover to DB cluster name in Region name completed.
  EVENT_RULE_ARN=$(aws events put-rule \
    --name ${AURORA_GLOBAL_CLUSTER} \
    --event-pattern \
    '{
       "source": ["aws.rds"],
       "detail": {
         "EventCategories": ["global-failover"],
         "EventID": ["RDS-EVENT-0185", "RDS-EVENT-0238"]
       }
     }' \
     --query 'RuleArn' \
     --output text
  )

  aws lambda add-permission \
    --function-name ${FUNCTION_NAME} \
    --statement-id ${AURORA_GLOBAL_CLUSTER} \
    --action 'lambda:InvokeFunction' \
    --principal 'events.amazonaws.com' \
    --source-arn ${EVENT_RULE_ARN}

  # Invoke the Lambda when event received
  aws events put-targets \
    --rule ${AURORA_GLOBAL_CLUSTER} \
    --targets \
    '
    [
      {
        "Id": "1",
        "Arn": "'${FUNCTION_ARN}'"
      }
    ]
    '
done
