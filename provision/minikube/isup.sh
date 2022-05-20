#!/usr/bin/env bash
# set -x

# when no arguments was given
if [ $# -eq 0 ]
then
  HOST=$(minikube ip).nip.io
else
  HOST=$0
fi


MAXRETRIES=50

declare -A SERVICES=( \
 ["https://keycloak.${HOST}/"]="realms/master/.well-known/openid-configuration" \
 ["https://grafana.${HOST}/"]="" \
 ["https://prometheus.${HOST}/"]="" \
 ["https://kubebox.${HOST}/"]="" \
 )

for SERVICE in ${!SERVICES[@]}; do
  RETRIES=MAXRETRIES
  # loop until we connect successfully or failed
  until curl -k -f -v ${SERVICE}${SERVICES[${SERVICE}]} >/dev/null 2>/dev/null
  do
    if [ $RETRIES == $MAXRETRIES]
    then
      echo -n "Waiting for services to start on ${URL}"
    fi

    RETRIES=$(($RETRIES - 1))
    if [ $RETRIES -eq 0 ]
    then
        echo "Failed to connect"
        exit 1
    fi
    # wait a bit
    echo -n "."
    sleep 1
  done
  echo ${SERVICE} is up
done
