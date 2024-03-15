#!/bin/bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

for REG in $(aws account list-regions --query 'Regions[?RegionOptStatus != `DISABLED`].RegionName' --output text);
do
  # Get all DHCP options that are tagged with red-hat-clustertype=rosa
  DHCP_OPTIONS_JSON=$(aws ec2 describe-dhcp-options --region "$REG" --filters Name=tag:red-hat-clustertype,Values=rosa  --query 'DhcpOptions[*]' --output json --no-cli-pager 2>/dev/null)

  # Iterate over all DHCP options
  DHCP_OPTIONS_IDS=( $(echo $DHCP_OPTIONS_JSON | jq -r '.[].DhcpOptionsId') )
  if [ ${#DHCP_OPTIONS_IDS[@]} -gt 0 ]; then
    echo "$REG region contains DHCP options that were not cleaned up [${#DHCP_OPTIONS_IDS[@]}]"
    for DHCP_OPTIONS_ID in "${DHCP_OPTIONS_IDS[@]}"; do
      # All ROSA resources are tagged with "kubernetes.io/cluster/<cluster-name>"="owned" therefore we can use this to find the VPC

      # Get the tag key and value from the found DHCP options
      VPC_TAG_KEY=$(echo $DHCP_OPTIONS_JSON | jq -r ".[] | select(.DhcpOptionsId == \"$DHCP_OPTIONS_ID\") | .Tags[] | select(.Key | startswith(\"kubernetes.io/cluster/\")) | .Key")
      VPC_TAG_VALUE=$(echo $DHCP_OPTIONS_JSON | jq -r ".[] | select(.DhcpOptionsId == \"$DHCP_OPTIONS_ID\") | .Tags[] | select(.Key | startswith(\"kubernetes.io/cluster/\")) | .Value")

      # Find VPC based on the tag and value matching the DHCP options
      VPC_ID=$(aws ec2 describe-vpcs --region "$REG" --filters Name=tag:"$VPC_TAG_KEY",Values="$VPC_TAG_VALUE" --query 'Vpcs[*].VpcId' --output text --no-cli-pager 2>/dev/null)

      # If no VPC was found, delete the DHCP options
      if [ -z "$VPC_ID" ]; then
        echo "Deleting DHCP options $DHCP_OPTIONS_ID as no VPC was found"
        aws ec2 delete-dhcp-options --region "$REG" --dhcp-options-id "$DHCP_OPTIONS_ID" --no-cli-pager
      fi
    done
  fi
done

