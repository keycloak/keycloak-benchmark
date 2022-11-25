#!/bin/bash

set -e

### DATASET PROVIDER - KEYCLOAK REST API SERVICE ###

create_realms () {
  if [ $# -eq 0 ]
  then
    REALM_COUNT=1
  else
    REALM_COUNT=$1
  fi
  CLIENT_COUNT=${2:-100}
  USER_COUNT=${3:-100}
  PASSWORD_HASHING=${4:-20000}
  KEYCLOAK_URI="${5:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"

  echo "Creating $REALM_COUNT realm/s with $CLIENT_COUNT client/s and $USER_COUNT user/s with $PASSWORD_HASHING password hash iterations."
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}create-realms?count=$REALM_COUNT&clients-per-realm=$CLIENT_COUNT&users-per-realm=$USER_COUNT&password-hash-iterations=$PASSWORD_HASHING"
    echo ""

}

create_clients () {
  KEYCLOAK_URI="${4:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Creating $1 client/s in realm $2"
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}create-clients?count=$1&realm-name=$2"
}

create_users () {
  KEYCLOAK_URI="${4:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Creating $1 user/s in realm $2"
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}create-users?count=$1&realm-name=$2"
}

create_events () {
  KEYCLOAK_URI="${4:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Creating $1 event/s in realm $2"
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}create-events?count=$1&realm-name=$2"
}

create_offline_sessions () {
  KEYCLOAK_URI="${4:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Creating $1 offline sessions in realm $2"
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}create-offline-sessions?count=$1&realm-name=$2"
}

delete_realms () {
  KEYCLOAK_URI="${3:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Deleting realm/s with prefix $1"
  check_dataset_status
  curl -ks "${KEYCLOAK_URI}remove-realms?remove-all=true&realm-prefix=$1"
}

# shellcheck disable=SC2120
dataset_provider_status () {
  KEYCLOAK_URI="${1:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Dataset provider status"
  curl -ks "${KEYCLOAK_URI}status"
  echo ""
}

dataset_provider_status_completed () {
  KEYCLOAK_URI="${2:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Dataset provider status of the last completed task"
  t=0
  STATUS_TIMEOUT=${1:-120}
  until curl -ks "${KEYCLOAK_URI}status-completed" | grep '"success":"true"' ; do
    if [[ $t -gt $STATUS_TIMEOUT ]]
     then
      echo "Status Polling timeout ${STATUS_TIMEOUT}s exceeded ";
      break
    fi
    sleep 1 && ((t=t+1))
    echo "Polling...${t}s"
  done
}

# shellcheck disable=SC2120
dataset_provider_clear_status_completed () {
  KEYCLOAK_URI="${1:-"https://keycloak.$(minikube ip).nip.io"}/realms/master/dataset/"
  echo "Dataset provider clears the status of the last completed task"
  curl -ks -X DELETE "${KEYCLOAK_URI}status-completed"
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
  echo "Dataset import to the local minikube Keycloak application."
  echo "Usage: "
  echo "1) create realm/s with clients, users and password hash iterations - run it with or without arguments: -r | --create-realms '10 100 100 20000' 'https://keycloak.url.com'"
  echo "2) create clients in specific realm: -c | --create-clients '100 realm-0' 'https://keycloak.url.com'"
  echo "3) create users in specific realm: -u | --create-users '100 realm-0' 'https://keycloak.url.com'"
  echo "4) create events in specific realm: -e | --create-events '100 realm-0' 'https://keycloak.url.com'"
  echo "5) create offline sessions in specific realm: -o | --create-offline-sessions '100 realm-0' 'https://keycloak.url.com'"
  echo "6) delete specific realm/s with prefix -d | --delete-realms realm 'https://keycloak.url.com'"
  echo "7) dataset provider status : with optional args in this order <keycloak_url> -s | --status 'https://keycloak.url.com'"
  echo "8) dataset provider status check of last completed job : with optional args in this order <timeout in seconds> <keycloak_url> | --status-completed 10 'https://keycloak.url.com' "
  echo "9) dataset provider clear status of last completed job : with optional args in this order <keycloak_url> | --clear-status-completed 'https://keycloak.url.com'"
  echo "10) dataset import script usage -h | --help"
}

main () {
  SHORT_ARGS=r::,c:,u:,e:,o:,d:,s,h
  LONG_ARGS=create-realms::,create-clients:,create-users:,create-events:,create-offline-sessions:,delete-realms:,help,status,status-completed,clear-status-completed
  ARGS=$(getopt -a -n dataset-import -o ${SHORT_ARGS} -l ${LONG_ARGS} -- "$@")

  eval set -- "$ARGS"

  echo -n "[$1] "
  case "$1" in
    -r|--create-realms)
      shift 2
      # shellcheck disable=SC2068
      create_realms ${@:2}
      exit 0
      ;;
    -c|--create-clients)
      create_clients $2 $3 $4
      exit 0
      ;;
    -u|--create-users)
      create_users $2 $3 $4
      exit 0
      ;;
    -e|--create-events)
      create_events $2 $3 $4
      exit 0
      ;;
    -o|--create-offline-sessions)
      create_offline_sessions $2 $3 $4
      exit 0
      ;;
    -d|--delete-realms)
      delete_realms $2 $3
      exit 0
      ;;
    -s|--status)
      dataset_provider_status $3
      exit 0
      ;;
    --status-completed)
      dataset_provider_status_completed $3 $4
      exit 0
      ;;
    --clear-status-completed)
      dataset_provider_clear_status_completed $3
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
}

## Start of script
main "$@"
