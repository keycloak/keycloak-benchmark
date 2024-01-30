#!/usr/bin/env bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

function printVpcDependencies() {
    vpc=$1
    region=$2
    aws ec2 describe-internet-gateways --region $region --filters 'Name=attachment.vpc-id,Values='$vpc | grep InternetGatewayId
    aws ec2 describe-subnets --region $region --filters 'Name=vpc-id,Values='$vpc | grep SubnetId
    aws ec2 describe-route-tables --region $region --filters 'Name=vpc-id,Values='$vpc | grep RouteTableId
    aws ec2 describe-network-acls --region $region --filters 'Name=vpc-id,Values='$vpc | grep NetworkAclId
    aws ec2 describe-vpc-peering-connections --region $region --filters 'Name=requester-vpc-info.vpc-id,Values='$vpc | grep VpcPeeringConnectionId
    aws ec2 describe-vpc-endpoints --region $region --filters 'Name=vpc-id,Values='$vpc | grep VpcEndpointId
    aws ec2 describe-nat-gateways --region $region --filter 'Name=vpc-id,Values='$vpc | grep NatGatewayId
    aws ec2 describe-security-groups --region $region --filters 'Name=vpc-id,Values='$vpc | grep GroupId
    aws ec2 describe-instances --region $region --filters 'Name=vpc-id,Values='$vpc | grep InstanceId
    aws ec2 describe-vpn-connections --region $region --filters 'Name=vpc-id,Values='$vpc | grep VpnConnectionId
    aws ec2 describe-vpn-gateways --region $region --filters 'Name=attachment.vpc-id,Values='$vpc | grep VpnGatewayId
    aws ec2 describe-network-interfaces --region $region --filters 'Name=vpc-id,Values='$vpc | grep NetworkInterfaceId
    aws ec2 describe-carrier-gateways --region $region --filters Name=vpc-id,Values=$vpc | grep CarrierGatewayId
    aws ec2 describe-local-gateway-route-table-vpc-associations --region $region --filters Name=vpc-id,Values=$vpc | grep LocalGatewayRouteTableVpcAssociationId
}

EXIT_CODE=0

for r in $(aws account list-regions --query 'Regions[?RegionOptStatus != `DISABLED`].RegionName' --output text);
do
  VPCS_JSON=$(aws ec2 describe-vpcs --region "$r" --query 'Vpcs[?IsDefault == `false`]' --output json --no-cli-pager 2>/dev/null)

  VPC_IDS=( $(echo $VPCS_JSON | jq -r '.[].VpcId') )
  if [ ${#VPC_IDS[@]} -gt 0 ]; then
    echo "$r region contains VPCs that were not cleaned up [${#VPC_IDS[@]}]"
    echo "--------------------------------------------------------------------"
    for VPC_ID in "${VPC_IDS[@]}"; do
    echo "---------------------"
      echo $VPCS_JSON | jq ".[] | select(.VpcId == \"$VPC_ID\")" | yq -P
      printVpcDependencies $VPC_ID $r
    echo "---------------------"
    done
    echo "--------------------------------------------------------------------"
    EXIT_CODE=1
  fi
done

exit $EXIT_CODE
