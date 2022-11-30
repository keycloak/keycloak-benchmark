#!/usr/bin/env bash
set -e

export timestamp="${timestamp:-$(date -uIseconds)}"

sutInfoDir="data/${timestamp}/system-under-test"

echo "Collecting information about system under test."

echo "- pods/keycloak-0"
mkdir -p "${sutInfoDir}/keycloak-0"

kubectl -n keycloak get pods/keycloak-0 -o yaml > pod.yaml 
cat pod.yaml | yq ".spec.containers[0].env" > "${sutInfoDir}/keycloak-0/env.yaml"

JAVA_OPTS_APPEND=$(cat "${sutInfoDir}/keycloak-0/env.yaml" | yq 'map(select(.name == "JAVA_OPTS_APPEND")) | .[] | .value')

cat <<EOF > "${sutInfoDir}/keycloak-0/parameters.yaml"
image: $(cat pod.yaml | yq ".status.containerStatuses[0].image")
imageID: $(cat pod.yaml | yq ".status.containerStatuses[0].imageID")
cpuLimits: $(cat pod.yaml | yq ".spec.containers[0].resources.limits.cpu")
memoryLimits: $(cat pod.yaml | yq ".spec.containers[0].resources.limits.memory")
heapInit: $(echo "$JAVA_OPTS_APPEND" | grep -oP '\-Xms\K\w+')
heapMax: $(echo "$JAVA_OPTS_APPEND" | grep -oP '\-Xmx\K\w+')
metaspaceInit: $(echo "$JAVA_OPTS_APPEND" | grep -oP '\-XX:MetaspaceSize=\K\w+')
metaspaceMax: $(echo "$JAVA_OPTS_APPEND" | grep -oP '\-XX:MaxMetaspaceSize=\K\w+')
EOF
rm pod.yaml

kubectl -n keycloak exec keycloak-0 -- cat /proc/cpuinfo | \
  sed "s/\s*:/:/g" | \
  sed "s/\s\([^[:space:]]*\):/_\1:/g" | \
  sed "/^processor/! s/\(.*\)/  \1/g" | \
  sed "s/^processor/- processor/g" \
> "${sutInfoDir}/keycloak-0/cpuinfo.yaml"

echo "- Kubernetes nodes"

for node in $(kubectl -n keycloak get nodes -o name); do 
  mkdir -p "${sutInfoDir}/${node}"
  kubectl -n keycloak get ${node} -o yaml > "${sutInfoDir}/node.yaml"
  cat "${sutInfoDir}/node.yaml" | yq ".status.nodeInfo" > "${sutInfoDir}/${node}/nodeInfo.yaml"
  cat "${sutInfoDir}/node.yaml" | yq ".status.capacity" > "${sutInfoDir}/${node}/capacity.yaml"
  cat "${sutInfoDir}/node.yaml" | yq ".status.allocatable" > "${sutInfoDir}/${node}/allocatable.yaml"
  cat "${sutInfoDir}/node.yaml" | yq ".status.conditions" > "${sutInfoDir}/${node}/conditions.yaml"
  rm "${sutInfoDir}/node.yaml"
done

echo "Information collected."

echo ""
cat "${sutInfoDir}/keycloak-0/parameters.yaml"
