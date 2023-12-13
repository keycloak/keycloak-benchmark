#!/bin/bash

EXIT_CODE=0

for r in $(aws account list-regions --query 'Regions[?RegionOptStatus != `DISABLED`].RegionName' --output text);
do
  VPCS=( $(aws ec2 describe-vpcs --region "$r" --query 'Vpcs[?IsDefault == `false`].VpcId' --output text --no-cli-pager 2>/dev/null) )

  if [ ${#VPCS[@]} -gt 0 ]; then
    echo "$r region contains VPCs that were not cleaned up [${#VPCS[@]}]"
    echo "--------------------------------------------------------------------"
    for vpc in "${VPCS[@]}"; do
    echo "---------------------"
      aws ec2 describe-vpcs --region "$r" --output yaml --query "Vpcs[?VpcId == \`$vpc\`]" --no-cli-pager
    echo "---------------------"
    done
    echo "--------------------------------------------------------------------"
    EXIT_CODE=1
  fi
done

exit $EXIT_CODE

