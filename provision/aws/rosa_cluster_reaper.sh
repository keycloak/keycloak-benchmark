#!/bin/bash
set -e

if [ -f ./.env ]; then
  source ./.env
fi

echo "Starting the ROSA cluster reaper at $(date -uIs)"

# Get a list of running ROSA clusters
CLUSTERS=$(rosa list clusters --output json | jq -r '.[] | select((.state == "ready") or (.state == "pending") or (.state == "waiting") or (.state == "error")) | .name')

if [ -z "$CLUSTERS" ]; then echo "Didn't find any ready state clusters, to purge"; exit; fi

# Loop through each cluster
for CLUSTER_NAME in $CLUSTERS; do
  echo "Checking Cluster: $CLUSTER_NAME"
  export CLUSTER_NAME=$CLUSTER_NAME
  #Login to the OpenShift Cluster
  ./rosa_oc_login.sh
  #Check if the 'keepalive' namespace exists in the specific cluster
  if oc get projects | grep -q "keepalive"; then
    echo "keepalive namespace exists in the cluster $CLUSTER_NAME, skipping deleting it."
  else
    echo "keepalive namespace doesn't exist in the cluster $CLUSTER_NAME, deleting it."
    #Delete the Individual Cluster
    ./rosa_delete_cluster.sh
  fi
done

echo "Finished reaping all possible clusters at $(date -uIs)"



