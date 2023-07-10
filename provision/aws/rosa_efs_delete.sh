#!/bin/bash
# don't use 'set -e' here, as we want to cleanup also half-installed EFS setups

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

AWS_REGION=$(rosa describe cluster --cluster "$CLUSTER_NAME" --output json | jq -r '.region.id')
echo "AWS_REGION: ${AWS_REGION}"

EFS=$(oc get sc/efs-sc -o jsonpath='{.parameters.fileSystemId}')

if [[ "$EFS" != "" ]]; then

  for MOUNT_TARGET in $(aws efs describe-mount-targets \
    --region=$AWS_REGION \
    --file-system-id=$EFS \
    --output json \
    | jq -r '.MountTargets[].MountTargetId'); do
    aws efs delete-mount-target --mount-target-id $MOUNT_TARGET --region $AWS_REGION
  done

  TIMEOUT=$(($(date +%s) + 600))
  while true ; do
      LIFECYCLE_STATE="$(aws efs describe-mount-targets \
                         --region=$AWS_REGION \
                         --file-system-id=$EFS \
                         --output json \
                         | jq -r '.MountTargets[].MountTargetId')"
      if [[ "${LIFECYCLE_STATE}" == "" ]]; then break; fi
      if (( TIMEOUT < $(date +%s))); then
        echo "Timeout exceeded"
        exit 1
      fi
      sleep 1
      echo -n '.'
  done

  for ACCESS_POINT in $(aws efs describe-access-points \
    --region=$AWS_REGION \
    --file-system-id=$EFS \
    --output json \
    | jq -r '.AccessPoints[].AccessPointId'); do
    aws efs delete-access-point --access-point-id $ACCESS_POINT --region $AWS_REGION
  done

  aws efs delete-file-system --file-system-id $EFS --region $AWS_REGION

  TIMEOUT=$(($(date +%s) + 600))
  while true ; do
      LIFECYCLE_STATE="$(aws efs describe-file-systems --file-system-id $EFS --region $AWS_REGION --output json | jq -r '.FileSystems[0].LifeCycleState')"
      if [[ "${LIFECYCLE_STATE}" == "" ]]; then break; fi
      if (( TIMEOUT < $(date +%s))); then
        echo "Timeout exceeded"
        exit 1
      fi
      sleep 1
      echo -n '.'
  done

fi

NODE=$(oc get nodes --selector=node-role.kubernetes.io/worker \
  -o jsonpath='{.items[0].metadata.name}')
VPC=$(aws ec2 describe-instances \
  --filters "Name=private-dns-name,Values=$NODE" \
  --output json \
  --query 'Reservations[*].Instances[*].{VpcId:VpcId}' \
  --region $AWS_REGION \
  | jq -r '.[0][0].VpcId')
CIDR=$(aws ec2 describe-vpcs \
  --filters "Name=vpc-id,Values=$VPC" \
  --query 'Vpcs[*].CidrBlock' \
  --output json \
  --region $AWS_REGION \
  | jq -r '.[0]')
SG=$(aws ec2 describe-instances --filters \
  "Name=private-dns-name,Values=$NODE" \
  --query 'Reservations[*].Instances[*].{SecurityGroups:SecurityGroups}' \
  --output json \
  --region $AWS_REGION \
  | jq -r '.[0][0].SecurityGroups[0].GroupId')
echo "CIDR - $CIDR,  SG - $SG"

aws ec2 revoke-security-group-ingress \
 --group-id $SG \
 --protocol tcp \
 --region $AWS_REGION \
 --port 2049 \
 --cidr $CIDR

cd efs

CCO_POD_NAME=$(oc get po -n openshift-cloud-credential-operator -l app=cloud-credential-operator -o jsonpath='{.items[*].metadata.name}')

oc cp -c cloud-credential-operator openshift-cloud-credential-operator/${CCO_POD_NAME}:/usr/bin/ccoctl ./ccoctl --retries=999

chmod 775 ./ccoctl

./ccoctl aws delete --name=${CLUSTER_NAME} --region=${AWS_REGION}

oc delete storageclass efs-sc

oc delete -n openshift-cluster-csi-drivers Subscription aws-efs-csi-driver-operator

oc delete -n openshift-cluster-csi-drivers Secret aws-efs-cloud-credentials

oc delete ClusterCSIDriver efs.csi.aws.com

for OPERATOR_GROUP in $(oc get -n openshift-cluster-csi-drivers OperatorGroup -o name); do
  oc delete -n openshift-cluster-csi-drivers $OPERATOR_GROUP
done
