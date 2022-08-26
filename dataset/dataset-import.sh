#!/bin/bash

set -e

### DATASET PROVIDER - KEYCLOAK REST API SERVICE ###

create_realms () {
  echo "Creating $1 realm/s "
  execute_command "create-realms?count=$1"
}

create_custom_realms () {
  echo "Creating $1 realm/s with $2 client/s and $3 user/s"
  execute_command "create-realms?count=$1&clients-per-realm=$2&users-per-realm=$3"
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
  curl -ks "${KEYCLOAK_URI}status"
  echo ""
}

check_dataset_status () {
  for i in {0..10}
  do
    if [[ $(curl -ks "${KEYCLOAK_URI}status" | grep "No task in progress") ]]
    then
      break
    elif [[ $(curl -ks "${KEYCLOAK_URI}status" | grep "Realm does not exist") ]]
    then
        echo "Realm master does not exist, please rebuild your Keycloak application from scratch."
        exit 1
    elif [[ $(curl -ks "${KEYCLOAK_URI}status" | grep "unknown_error") ]]
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

execute_command () {
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}$1"
  echo ""
}

help () {
  echo "Dataset import to the local minikube Keycloak application."
  echo "Usage: "
  echo "1) create realm/s with default values: -r | --create-realms 10"
  echo "2) create custom realm/s (clients,users): -R | --create-custom-realms '10 100 100'"
  echo "3) create clients in specific realm: -c | --create-clients '100 realm-0'"
  echo "4) create users in specific realm: -u | --create-users '100 realm-0'"
  echo "5) create events in specific realm: -e | --create-events '100 realm-0'"
  echo "7) create offline sessions in specific realm: -o | --create-offline-sessions '100 realm-0'"
  echo "8) delete specific realm/s with prefix -d | --delete-realms realm"
  echo "9) dataset provider status -s | --status"
  echo "10) dataset import script usage -h | --help"
}

main () {
  KEYCLOAK_URI="https://keycloak.$(minikube ip).nip.io/realms/master/dataset/"
  SHORT_ARGS=r:,R:,c:,u:,e:,o:,d:,s,h
  LONG_ARGS=create-realms:,create-custom-realms:,create-clients:,create-users:,create-events:,create-offline-sessions:,delete-realms:,help,status
  ARGS=$(getopt -a -n dataset-import -o ${SHORT_ARGS} -l ${LONG_ARGS} -- "$@")

  eval set -- "$ARGS"

  while true
  do
    case "$1" in
      -r|--create-realms)
        create_realms $2
        exit 0
        ;;
      -R|--create-custom-realms)
        create_custom_realms $2 $3 $4
        exit 0
        ;;
      -c|--create-clients)
        create_clients $2 $3
        exit 0
        ;;
      -u|--create-users)
        create_users $2 $3
        exit 0
        ;;
      -e|--create-events)
        create_events $2 $3
        exit 0
        ;;
      -o|--create-offline-sessions)
        create_offline_sessions $2 $3
        exit 0
        ;;
      -d|--delete-realms)
        delete_realms $2
        exit 0
        ;;
      -s|--status)
        dataset_provider_status
        exit 0
        ;;
      -h|--help)
        help
        exit 0
        ;;
      *)
        echo "Unknown option, use -h | --help to see permitted options."
        exit 1
    esac
  done
}

## Start of script
main "$@"
