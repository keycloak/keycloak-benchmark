#!/bin/bash
set -e

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

# Simple script to test that the failover client. endpoint is pointing to the primary. subdomain.
DOMAIN=$1

if [ -z "${DOMAIN}" ]; then
  echo "Parent domain containing 'client.' 'primary.' and 'backup.' subdomains must be specified"
  exit 22
fi

CLIENT_IPS=$(dig +short client.${DOMAIN} | sort)
PRIMARY_IPS=$(dig +short primary.${DOMAIN} | sort)
BACKUP_IPS=$(dig +short backup.${DOMAIN} | sort)

[[ "${CLIENT_IPS}" == "${PRIMARY_IPS}" && "${CLIENT_IPS}" != "${BACKUP_IPS}" ]]
