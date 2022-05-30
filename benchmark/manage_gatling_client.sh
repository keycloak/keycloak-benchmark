#!/bin/bash
set -e
set -o pipefail
# set -x
#Functions
function usage {
  echo "Setup of test clients for the benchmark test with a pre-defined secret"
  echo "Usage: $(basename $0) -k KEYCLOAK_HOME -r REALM_NAME -c CLIENT_ID -s SERVER_URL [-d]" 2>&1
  echo '-k keycloak home path ex: /home/opt/keycloak-18.0.0 : default value KEYCLOAK_HOME env variable'
  echo '-r realm : default value realm-0'
  echo '-c service account enabled client name : default value gatling'
  echo '-s keycloak host server url : default value http://localhost:8081/auth'
  echo '-d delete the client and realm before re-creating them: default value is false'
  exit 1
}

function install_kcb {
  echo "Installing kcadm.sh"
  export PATH=$PATH:$KEYCLOAK_HOME/bin
}

function create_realm {
  if kcadm.sh get realms/${REALM_NAME}; then
    echo "INFO: Skipping Realm creation as realm exists"
  else
    echo "INFO: Creating Realm with realm id: ${REALM_NAME}"
    kcadm.sh create realms -s realm=$REALM_NAME -s enabled=true -o
  fi
}

function create_client_assign_roles {
  #Create the client application with Service Account Roles setup
  CID=$(kcadm.sh create clients -r $REALM_NAME -s clientId=$CLIENT_ID -s enabled=true -i)
  echo "INFO: Created New ${CLIENT_ID} Client with Client ID ${CID}"
  #Update the client with necessary attributes
  kcadm.sh update clients/$CID -r $REALM_NAME  -s "secret=setup-for-benchmark" -s serviceAccountsEnabled=true  -s publicClient=false -s 'redirectUris=["http://localhost:8081"]'
  kcadm.sh get clients/$CID -r $REALM_NAME --fields clientId,id,redirectUris,publicClient,serviceAccountsEnabled,secret

  #Verify if the service account user exists
  kcadm.sh get users -r $REALM_NAME -q username=service-account-$CLIENT_ID --fields id,username,enabled

  #Assign the necessary Service Account based roles to the client
  kcadm.sh add-roles -r $REALM_NAME --uusername service-account-$CLIENT_ID --cclientid realm-management --rolename manage-clients --rolename view-users --rolename manage-realm --rolename manage-users
  #kcadm.sh get-roles -r $REALM_NAME --uusername service-account-$CLIENT_ID --cclientid realm-management
}

function delete_entities {
  if kcadm.sh get realms/${REALM_NAME}; then
    kcadm.sh delete realms/${REALM_NAME}
  fi
}

#main()
#setting default values
OPTIND=1
REALM_NAME=realm-0
CLIENT_ID=gatling
SERVER_URL="http://localhost:8081/auth"
DELETE_ENTITIES='false'

while getopts "s:k:r:c:d:" arg; do
  case $arg in
    s) SERVER_URL="$OPTARG";
        ;;
    k) KEYCLOAK_HOME="$OPTARG";
        ;;
    r) REALM_NAME="$OPTARG";
        ;;
    c) CLIENT_ID="$OPTARG";
        ;;
    d) DELETE_ENTITIES="$OPTARG";
        ;;
    \?) echo "ERROR: Invalid option: -${OPTARG}" >&2
        usage
        ;;
  esac
done

#Handle missing opt args
if (( ${OPTIND} == 1 ))
then
  echo "ERROR: No Options Specified"
  usage
fi
shift $(( OPTIND -1 ))

#Setup the KCB client tool in the system PATH
install_kcb

#Create or Re-Create Client and Realm
if ${DELETE_ENTITIES}; then
 echo "INFO: deleting the Client and Realm"
 delete_entities
else
 echo "WARN: not deleting the Client and Realm"
fi

create_realm
create_client_assign_roles