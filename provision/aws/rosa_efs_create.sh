#!/usr/bin/env bash
# This automated the setup of EFS as a RWX storage in ROSA. It is based on the following information:
# * https://access.redhat.com/articles/6966373
# * https://mobb.ninja/docs/rosa/aws-efs/
# * https://docs.openshift.com/rosa/storage/container_storage_interface/osd-persistent-storage-aws-efs-csi.html

set -eo pipefail

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

if [ -f ./.env ]; then
  source ./.env
fi

AWS_REGION=${REGION}
OIDC_PROVIDER=$(oc get authentication.config.openshift.io cluster -o json \
   | jq -r .spec.serviceAccountIssuer| sed -e "s/^https:\/\///")
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

cd efs

echo "Installing EFS CSI driver operator."
oc apply -f aws-efs-csi-driver-operator.yaml

# We've seen that the 'oc get...' has returned two entries in the past. Let's make sure that everything settled before we retrieve the one pod which is ready
kubectl wait --for=condition=Available --timeout=300s -n openshift-cloud-credential-operator deployment/cloud-credential-operator
CCO_POD_NAME=$(oc get po -n openshift-cloud-credential-operator -l app=cloud-credential-operator -o jsonpath='{range .items[*]}{.status.containerStatuses[*].ready.true}{.metadata.name}{ "\n"}{end}')

oc cp -c cloud-credential-operator openshift-cloud-credential-operator/${CCO_POD_NAME}:/usr/bin/ccoctl ./ccoctl --retries=999

chmod 775 ./ccoctl

./ccoctl aws create-iam-roles --name=${CLUSTER_NAME} --region=${AWS_REGION} --credentials-requests-dir=credentialRequests/ --identity-provider-arn=arn:aws:iam::${AWS_ACCOUNT_ID}:oidc-provider/${OIDC_PROVIDER}

# if the CredentialsRequest was processed, manifests/openshift-cluster-csi-drivers-aws-efs-cloud-credentials-credentials.yaml file should be created
# create credentials if present
if [[ -f manifests/openshift-cluster-csi-drivers-aws-efs-cloud-credentials-credentials.yaml ]]; then
    oc apply -f manifests/openshift-cluster-csi-drivers-aws-efs-cloud-credentials-credentials.yaml
fi

oc apply -f efs-csi-aws-com-cluster-csi-driver.yaml

kubectl wait --for=condition=AWSEFSDriverNodeServiceControllerAvailable --timeout=300s clustercsidriver.operator.openshift.io/efs.csi.aws.com
kubectl wait --for=condition=AWSEFSDriverControllerServiceControllerAvailable --timeout=300s clustercsidriver.operator.openshift.io/efs.csi.aws.com

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

EXISTS=$(aws ec2 describe-security-groups --group-id $SG  --region $AWS_REGION --output json | jq -r '.SecurityGroups[] | .IpPermissions[] | select(.FromPort == 2049 and .ToPort == 2049 and .IpProtocol == "tcp")')
if [[ -z "$EXISTS" ]]; then
    aws ec2 authorize-security-group-ingress \
     --group-id $SG \
     --protocol tcp \
     --port 2049 \
     --output json \
     --region $AWS_REGION \
     --cidr $CIDR | jq .
fi

SUBNET=$(aws ec2 describe-subnets \
  --filters Name=vpc-id,Values=$VPC Name=tag:Name,Values='*-private*' \
  --query 'Subnets[*].{SubnetId:SubnetId}' \
  --output json \
  --region $AWS_REGION \
  | jq -r '.[0].SubnetId')
AWS_ZONE=$(aws ec2 describe-subnets --filters Name=subnet-id,Values=$SUBNET \
  --output json \
  --region $AWS_REGION | jq -r '.Subnets[0].AvailabilityZone')

EFS=$(aws efs describe-file-systems --region $AWS_REGION --output json --creation-token efs-token-${CLUSTER_NAME} | jq -r '.FileSystems[] | .FileSystemId')
if [[ -z "$EFS" ]]; then
    EFS=$(aws efs create-file-system --creation-token efs-token-${CLUSTER_NAME} \
     --availability-zone-name $AWS_ZONE \
     --output json \
     --tags Key=Name,Value=${CLUSTER_NAME} \
     --region $AWS_REGION \
     --encrypted | jq -r '.FileSystemId')
fi

echo $EFS

cat <<EOF | oc apply -f -
kind: StorageClass
apiVersion: storage.k8s.io/v1
metadata:
  name: efs-sc
provisioner: efs.csi.aws.com
parameters:
  provisioningMode: efs-ap
  fileSystemId: $EFS
  directoryPerms: "700"
  gidRangeStart: "1000"
  gidRangeEnd: "2000"
  basePath: "/dynamic_provisioning"
EOF

TIMEOUT=$(($(date +%s) + 600))
while true; do
    LIFECYCLE_STATE="$(aws efs describe-file-systems --file-system-id $EFS --region $AWS_REGION --output json | jq -r '.FileSystems[0].LifeCycleState')"
    if [[ "${LIFECYCLE_STATE}" == "available" ]]; then break; fi
    if (( TIMEOUT < $(date +%s))); then
      echo "Timeout exceeded"
      exit 1
    fi
    sleep 1
    echo -n '.'
done

for SUBNET in $(aws ec2 describe-subnets \
  --filters Name=vpc-id,Values=$VPC Name=tag:Name,Values='*-private*' \
  --query 'Subnets[*].{SubnetId:SubnetId}' \
  --output json \
  --region $AWS_REGION \
  | jq -r '.[].SubnetId'); do \
    MOUNT_TARGET=$(aws efs describe-mount-targets --output json --file-system-id $EFS --region $AWS_REGION | jq -r '.MountTargets[] | .MountTargetId')
    if [[ -z "$MOUNT_TARGET" ]]; then
        MOUNT_TARGET=$(aws efs create-mount-target --file-system-id $EFS \
         --subnet-id $SUBNET --security-groups $SG \
         --output json \
         --region $AWS_REGION \
         | jq -r '.MountTargetId'); \
    fi
    echo $MOUNT_TARGET; \
done
