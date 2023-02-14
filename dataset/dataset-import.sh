#!/bin/bash

set -e
set -o pipefail

### DATASET PROVIDER - KEYCLOAK REST API SERVICE ###

set_environment_variables () {
  ACTION="help"
  REALM_COUNT="1"
  REALM_NAME="realm-0"
  CLIENTS_COUNT="100"
  USERS_COUNT="100"
  EVENTS_COUNT="100"
  SESSIONS_COUNT="100"
  HASH_ITERATIONS="20000"
  KEYCLOAK_URI="https://keycloak.$(minikube ip || echo 'unknown').nip.io/realms/master/dataset"
  REALM_PREFIX="realm"
  STATUS_TIMEOUT="120"

  while getopts ":a:r:n:c:u:e:o:i:p:l:t:" OPT
  do
    case $OPT in
      a)
        ACTION=$OPTARG
        ;;
      r)
        REALM_COUNT=$OPTARG
        ;;
      n)
        REALM_NAME=$OPTARG
        ;;
      c)
        CLIENTS_COUNT=$OPTARG
        ;;
      u)
        USERS_COUNT=$OPTARG
        ;;
      e)
        EVENTS_COUNT=$OPTARG
        ;;
      o)
        SESSIONS_COUNT=$OPTARG
        ;;
      i)
        HASH_ITERATIONS=$OPTARG
        ;;
      p)
        REALM_PREFIX=$OPTARG
        ;;
      l)
        KEYCLOAK_URI=$OPTARG
        ;;
      t)
        STATUS_TIMEOUT=$OPTARG
        ;;
      ?)
        echo "Invalid option: $OPT, read the usage carefully -> "
        help
        exit 1
    esac
  done
}

create_realms () {
  echo "Creating $1 realm/s with $2 client/s and $3 user/s with $4 password hash iterations."
  execute_command "create-realms?count=$1&clients-per-realm=$2&users-per-realm=$3&password-hash-iterations=$4"
}

create_clients () {
  echo "Creating $1 client/s in realm $2"
  execute_command "create-clients?count=$1&realm-name=$2"
}

create_users () {
  echo "Creating $1 user/s in realm $2"
  execute_command "create-users?count=$1&realm-name=$2"
}

create_events () {
  echo "Creating $1 event/s in realm $2"
  execute_command "create-events?count=$1&realm-name=$2"
}

create_offline_sessions () {
  echo "Creating $1 offline sessions in realm $2"
  execute_command "create-offline-sessions?count=$1&realm-name=$2"
}

delete_realms () {
  echo "Deleting realm/s with prefix $1"
  execute_command "remove-realms?remove-all=true&realm-prefix=$1"
}

dataset_provider_status () {
  echo "Dataset provider status"
  execute_command "status"
}

dataset_provider_status_completed () {
  echo "Dataset provider status of the last completed task"
  t=0
  RESPONSE=$(execute_command "status-completed")
  until [[ $(echo $RESPONSE | grep '"success":"true"') ]]
  do
    if [[ $t -gt $1 ]]
     then
      echo "Status Polling timeout ${1}s exceeded ";
      break
    fi
    sleep 1 && ((t=t+1))
    echo "Polling...${t}s"
  done
  echo $RESPONSE
}

dataset_provider_clear_status_completed () {
  echo "Dataset provider clears the status of the last completed task"
  execute_command "status-completed" "-X DELETE"
}

execute_command () {
  if [[ ! $1 =~ "status" ]]
  then
    check_dataset_status
  fi
  curl -ks $2 "${KEYCLOAK_URI}/$1"
  echo ""
}

check_dataset_status () {
  for i in {0..10}
  do
    if [[ $(dataset_provider_status | grep "No task in progress") ]]
    then
      break
    elif [[ $(dataset_provider_status | grep "Realm does not exist") ]]
    then
        echo "Realm master does not exist, please rebuild your Keycloak application from scratch."
        exit 1
    elif [[ $(dataset_provider_status | grep "unknown_error") ]]
    then
        echo "Unknown error occurred, please check your Keycloak instance for more info."
        exit 1
    else
      if [[ $i -eq 10 ]]
      then
        echo "Keycloak dataset provider is busy, please try it again later."
        exit 1
      else
        echo "Waiting..."
        sleep 3s
      fi
    fi
  done
}

help () {
  echo "Dataset import to the local minikube Keycloak application - usage:"
  echo "1) create realm/s with clients, users and password hash iterations - run -a (action) with or without other arguments: -a create-realms -r 10 -c 100 -u 100 -i 20000 -l 'https://keycloak.url.com'"
  echo "2) create clients in specific realm: -a create-clients -c 100 -n realm-0 -l 'https://keycloak.url.com'"
  echo "3) create users in specific realm: -a create-users -u 100 -n realm-0 -l 'https://keycloak.url.com'"
  echo "4) create events in specific realm: -a create-events -e 100 -n realm-0 -l 'https://keycloak.url.com'"
  echo "5) create offline sessions in specific realm: -a create-offline-sessions -o 100 -n realm-0' -l 'https://keycloak.url.com'"
  echo "6) delete specific realm/s with prefix -a delete-realms -p realm -l 'https://keycloak.url.com'"
  echo "7) dataset provider status -a status 'https://keycloak.url.com'"
  echo "8) dataset provider status check of last completed job -a status-completed -t 10 -l 'https://keycloak.url.com'"
  echo "9) dataset provider clear status of last completed job -a clear-status-completed -l 'https://keycloak.url.com'"
  echo "10) dataset import script usage -a help"
}

main () {
  set_environment_variables $@

  echo "Action: [$ACTION] "
  case "$ACTION" in
    create-realms)
      create_realms $REALM_COUNT $CLIENTS_COUNT $USERS_COUNT $HASH_ITERATIONS
      exit 0
      ;;
    create-clients)
      create_clients $CLIENTS_COUNT $REALM_NAME
      exit 0
      ;;
    create-users)
      create_users $USERS_COUNT $REALM_NAME
      exit 0
      ;;
    create-events)
      create_events $EVENTS_COUNT $REALM_NAME
      exit 0
      ;;
    create-offline-sessions)
      create_offline_sessions $SESSIONS_COUNT $REALM_NAME
      exit 0
      ;;
    delete-realms)
      delete_realms $REALM_PREFIX
      exit 0
      ;;
    status)
      dataset_provider_status
      exit 0
      ;;
    status-completed)
      dataset_provider_status_completed $STATUS_TIMEOUT
      exit 0
      ;;
    clear-status-completed)
      dataset_provider_clear_status_completed
      exit 0
      ;;
    help)
      help
      exit 0
      ;;
    *)
      echo "Action doesn't exist: $ACTION, read the usage carefully -> "
      help
      exit 1
      ;;
  esac
}

## Start of script
main "$@"
