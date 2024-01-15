#!/usr/bin/env bash

set -euo pipefail

if [ "${1-}" == "cockroach-operator" ]; then

  RETRIES=100
  # loop until we connect successfully or failed

  if [ "${2-}" == "reset" ] && [ "$(kubectl get secrets cockroach-root -n keycloak -o name | wc -l)" == "0" ]; then
    exit 0
  fi

  until [ "$(kubectl get secrets cockroach-root -n keycloak -o name | wc -l)" == "1" ]
  do
    RETRIES=$(($RETRIES - 1))
    if [ $RETRIES -eq 0 ]
    then
        echo "Failed waiting for cockroach-root secret to appear"
        exit 1
    fi

    echo -n "."
    sleep 5
  done

  # The files tls.crt/tls.key have been originally created by the CockroachDB operator.
  # In order to use the key with the PostgreSQL JDBC driver, it needs to be converted to the PKCS-12 or PKCKS-12 format.
  # See https://jdbc.postgresql.org/documentation/use/ for more information.

  mkdir -p crdb
  kubectl get secrets cockroach-root -n keycloak -o "jsonpath={.data['tls\.key']}" | base64 -d > crdb/tls.key
  kubectl get secrets cockroach-root -n keycloak -o "jsonpath={.data['tls\.crt']}" | base64 -d > crdb/tls.crt
  kubectl get secrets cockroach-root -n keycloak -o "jsonpath={.data['ca\.crt']}" | base64 -d > crdb/ca.crt
  openssl pkcs8 -topk8 -inform PEM -in crdb/tls.key -outform DER -out crdb/tls.pk8 -v1 PBE-MD5-DES -nocrypt
  kubectl create secret generic cockroach-root-jdbc --from-file=tls.pk8=crdb/tls.pk8 --from-file=tls.crt=crdb/tls.crt --from-file=ca.crt=crdb/ca.crt  -n keycloak --dry-run=client -o yaml | kubectl apply -f -

  until [ "$(kubectl get pods -n keycloak | grep -E -c "cockroach-[0-9]+.*1/1 ")" -ge "3" ]
  do
    RETRIES=$(($RETRIES - 1))
    if [ $RETRIES -eq 0 ]
    then
        echo "Failed waiting for cockroach pods to appear"
        exit 1
    fi

    if [ "${2-}" == "reset" ] && [ "$(kubectl get pods -n keycloak | grep -E -c "cockroach-[0-9]+.*1/1 ")" == "0" ]; then
      exit 0
    fi

    echo -n "."
    sleep 5
  done

  kubectl wait pods -n keycloak -l app=cockroach-client-secure --for condition=Ready --timeout=90s

  if [ "${2-}" == "reset" ] || [ "$(kubectl -n keycloak exec cockroach-client-secure-0 -- ./cockroach sql --certs-dir=/cockroach/cockroach-certs --host=cockroach-public -e 'SELECT datname FROM pg_database;' | grep -c keycloak)" -eq "0" ]; then
    # this command needs "-it" to be able to pipe the contents into SQL console
    kubectl -n keycloak exec -it cockroach-client-secure-0 -- ./cockroach sql --certs-dir=/cockroach/cockroach-certs --host=cockroach-public < keycloak/config/cockroach-initdb-secure.sql
  fi

fi
