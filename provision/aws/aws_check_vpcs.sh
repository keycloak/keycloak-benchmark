#!/usr/bin/env bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

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
    echo "---------------------"
    done
    echo "--------------------------------------------------------------------"
    EXIT_CODE=1
  fi
done

exit $EXIT_CODE

