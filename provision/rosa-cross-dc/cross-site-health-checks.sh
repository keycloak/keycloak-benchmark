#!/usr/bin/env bash

# Default values
namespace="runner-keycloak"
keycloak_lb_url="temp-lb-url"
keycloak_site_url="temp-site-url"
infinispan_rest_url="temp-rest-url"
infinispan_user="developer"
infinispan_pwd="password-is-not-set"

# Usage function to display help for the script
usage() {
    echo "Usage: $0 [-n namespace] [-l keycloak_lb_url] [-k keycloak_site_url] [-i infinispan_rest_url] [-u infinispan_user] [-p infinispan_pwd] [-c expected_ispn_count]"
    echo "  -n namespace: Kubernetes namespace"
    echo "  -l keycloak_lb_url: Keycloak Load Balancer URL"
    echo "  -k keycloak_site_url: Keycloak Site URL"
    echo "  -i infinispan_rest_url: Infinispan REST URL"
    echo "  -u infinispan_user: Infinispan user"
    echo "  -p infinispan_pwd: Infinispan password"
    echo "  -c expected_ispn_count: Expected Node Count in the Infinispan cluster"
    exit 1
}

# Exit if no arguments are provided
if [ $# -eq 0 ]; then
    usage
    exit 1
fi

# Parse input arguments
while getopts ":n:l:k:i:u:p:c:h" opt; do
  case ${opt} in
    n ) namespace=$OPTARG
          ;;
    l ) keycloak_lb_url=$OPTARG
      ;;
    k ) keycloak_site_url=$OPTARG
      ;;
    i ) infinispan_rest_url=$OPTARG
          ;;
    u ) infinispan_user=$OPTARG
      ;;
    p ) infinispan_pwd=$OPTARG
      ;;
    c ) expected_ispn_count=$OPTARG
      ;;
    h ) usage
      ;;
    \? ) echo "Invalid option: $OPTARG" 1>&2
         usage
         exit 1
      ;;
    : ) echo "Invalid option: $OPTARG requires an argument" 1>&2
        usage
        exit 1
      ;;
  esac
done
shift $((OPTIND -1))

# Dependencies check
for cmd in curl jq oc; do
    if ! command -v $cmd &> /dev/null; then
        echo "Error: Command $cmd is not available."
        exit 1
    fi
done

health_check() {
    local url=$1
    local parse_json=${2:-false}  # Optional second argument; default is 'false'

    echo "Checking health for: $url"
    if [ "$parse_json" = true ]; then
        # If parsing JSON is requested, use jq to parse the response
        response=$(curl -sk "$url")
        echo "$response" | jq . 2>/dev/null
        if [ ${PIPESTATUS[0]} -ne 0 ]; then
            echo "Error parsing JSON from $url"
            return 1
        fi
    else
        # If parsing JSON is not requested, just display the raw response
        curl -sk "$url" > /dev/null
        if [ $? -eq 0 ]; then
        	echo -e '\033[0;32m"HEALTHY"\033[0m'
		else
    		echo "Health check failed for: $url"
		fi
    fi
    echo
}

echo "Verify the Keycloak Load Balancer health check"
health_check "$keycloak_lb_url"/lb-check

echo "Verify the Load Balancer health check on the Site"
health_check "$keycloak_site_url"/lb-check

echo "Verify the default cache manager health in external ISPN"
health_check "$infinispan_rest_url"/rest/v2/cache-managers/default/health/status

echo "Verify individual cache health"
curl -u "$infinispan_user":"$infinispan_pwd" -sk "$infinispan_rest_url"/rest/v2/cache-managers/default/health \
 | jq 'if .cluster_health.health_status == "HEALTHY" and (all(.cache_health[].status; . == "HEALTHY")) then "HEALTHY" else "UNHEALTHY" end'
echo

echo "ISPN Cluster Distribution"
# shellcheck disable=SC2086
curl -u "$infinispan_user":"$infinispan_pwd" -sk $infinispan_rest_url/rest/v2/cluster\?action\=distribution \
 | jq --argjson expectedCount "$expected_ispn_count" 'if map(select(.node_addresses | length > 0)) | length == $expectedCount then "HEALTHY" else "UNHEALTHY" end'
echo

echo "ISPN Overall Status"
oc get infinispan -n runner-keycloak -o json  \
| jq '.items[].status.conditions' \
| jq 'map({(.type): .status})' \
| jq 'reduce .[] as $item ([]; . + [keys[] | select($item[.] != "True")]) | if length == 0 then "HEALTHY" else "UNHEALTHY: " + (join(", ")) end'
echo

echo "Verify for Keycloak condition in ROSA cluster"
oc wait --for=condition=Ready --timeout=10s keycloaks.k8s.keycloak.org/keycloak -n "$namespace"
oc wait --for=condition=RollingUpdate=False --timeout=10s keycloaks.k8s.keycloak.org/keycloak -n "$namespace"
