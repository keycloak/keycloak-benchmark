#!/usr/bin/env bash

# Default values
domain="temp-domain"
infinispan_user="developer"
infinispan_pwd="password-is-not-set"
infinispan_url_suffix="temp-suffix"
namespace="runner-keycloak"

# Usage function to display help for the script
usage() {
    echo "Usage: $0 [-d domain] [-u infinispan_user] [-p infinispan_pwd] [-s infinispan_url_suffix] [-c expected_count] [-n namespace]"
    echo "  -d domain: Keycloak domain"
    echo "  -u infinispan_user: Infinispan user"
    echo "  -p infinispan_pwd: Infinispan password"
    echo "  -s infinispan_url_suffix: Infinispan URL suffix"
    echo "  -c expected_count: Expected Node Count in the Infinispan cluster"
    echo "  -n namespace: Kubernetes namespace"
    exit 1
}

# Exit if no arguments are provided
if [ $# -eq 0 ]; then
    usage
    exit 1
fi

# Parse input arguments
while getopts ":d:u:p:s:n:c:h" opt; do
  case ${opt} in
    d ) domain=$OPTARG
      ;;
    u ) infinispan_user=$OPTARG
      ;;
    p ) infinispan_pwd=$OPTARG
      ;;
    s ) infinispan_url_suffix=$OPTARG
      ;;
    n ) namespace=$OPTARG
      ;;
    c ) expected_count=$OPTARG
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


# Base URLs
keycloak_lb_url="https://client.$domain"
keycloak_site_a_url="https://primary.$domain"
keycloak_site_b_url="https://backup.$domain"
infinispan_rest_url="https://infinispan-external-runner-keycloak.apps.$infinispan_url_suffix"

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
health_check $keycloak_lb_url/lb-check

echo "Verify the Load Balancer health check on Site A and Site B"
health_check $keycloak_site_a_url/lb-check
health_check $keycloak_site_b_url/lb-check

echo "Verify the default cache manager health in external ISPN"
health_check $infinispan_rest_url/rest/v2/cache-managers/default/health/status

echo "Verify individual cache health"
curl -u $infinispan_user:$infinispan_pwd -sk $infinispan_rest_url/rest/v2/cache-managers/default/health \
 | jq 'if .cluster_health.health_status == "HEALTHY" and (all(.cache_health[].status; . == "HEALTHY")) then "HEALTHY" else "UNHEALTHY" end'
echo

echo "ISPN Cluster Distribution"
curl -u $infinispan_user:$infinispan_pwd -sk $infinispan_rest_url/rest/v2/cluster\?action\=distribution \
 | jq --argjson expectedCount $expected_count 'if map(select(.node_addresses | length > 0)) | length == $expectedCount then "HEALTHY" else "UNHEALTHY" end'
echo

echo "ISPN Overall Status"
oc get infinispan -n runner-keycloak -o json  \
| jq '.items[].status.conditions' \
| jq 'map({(.type): .status})' \
| jq 'reduce .[] as $item ([]; . + [keys[] | select($item[.] != "True")]) | if length == 0 then "HEALTHY" else "UNHEALTHY: " + (join(", ")) end'
echo

echo "Verify for Keycloak condition in ROSA cluster"
oc wait --for=condition=Ready --timeout=10s keycloaks.k8s.keycloak.org/keycloak -n runner-keycloak
oc wait --for=condition=RollingUpdate=False --timeout=10s keycloaks.k8s.keycloak.org/keycloak -n runner-keycloak
