#!/usr/bin/env bash
set -e
set -o pipefail
# set -x

#Functions
function usage {
  echo "Setup of test clients for the benchmark test with a pre-defined secret"
  echo "Usage: $(basename $0) -k KEYCLOAK_HOME -r REALM_NAME -c CLIENT_ID [-d]" 2>&1
  echo "-k keycloak home path ex: /home/opt/keycloak-18.0.0 : default value KEYCLOAK_HOME env variable"
  echo "-d delete the client and realm before re-creating them: default value is false"
  echo "-r realm : default value test-realm"
  echo "-c service account enabled client name : default value gatling"
  echo "-d delete the client and realm : default value is false"
  echo "-u user : default username user-0. The user's password will always be the username plus the suffix -password, ex: for user-0 this would be user-0-password"
  exit 1
}

function set_kcb_in_path {
  echo -e "Setting up kcadm.sh in PATH\n"
  export PATH=$PATH:$KEYCLOAK_HOME/bin
}

function create_realm {
  if kcadm.sh get realms/${REALM_NAME} | grep -q ${REALM_NAME}; then
    echo -e "INFO: Skipping Realm creation as realm exists\n"
  else
    echo -e "INFO: Creating Realm with realm id: ${REALM_NAME}"
    kcadm.sh create realms -s realm=$REALM_NAME -s enabled=true -o > /dev/null
  fi
}

function create_service_enabled_client_assign_roles {
  CID=$(kcadm.sh create clients -r $REALM_NAME -i -f - <<EOF
{
  "clientId": "$CLIENT_ID",
  "enabled": true,
  "clientAuthenticatorType": "client-secret",
  "secret": "setup-for-benchmark",
  "redirectUris": [
    "*"
  ],
  "serviceAccountsEnabled": true,
  "publicClient": false,
  "protocol": "openid-connect",
  "attributes": {
    "post.logout.redirect.uris": "+"
  }
}
EOF
)
  echo "INFO: Created New ${CLIENT_ID} Client with Client ID ${CID}"
  #Assign the necessary Service Account based roles to the client
  kcadm.sh add-roles -r $REALM_NAME --uusername service-account-$CLIENT_ID --cclientid realm-management --rolename manage-clients --rolename view-users --rolename manage-realm --rolename manage-users
}

function create_oidc_client {
  #Create the client application with OIDC for Auth Code scenarios with confidential client secret
  CID=$(kcadm.sh create clients -r $REALM_NAME -i -f - <<EOF
{
  "clientId": "client-0",
  "enabled": true,
  "clientAuthenticatorType": "client-secret",
  "secret": "client-0-secret",
  "redirectUris": [
    "*"
  ],
  "serviceAccountsEnabled": true,
  "publicClient": false,
  "protocol": "openid-connect",
  "attributes": {
    "post.logout.redirect.uris": "+"
  }
}
EOF
)
  echo "INFO: Created New client-0 Client"
}

function create_user {
 kcadm.sh create users -s username=$USER_NAME -s enabled=true -s firstName=Firstname -s lastName=Lastname -s email=$USER_NAME@keycloak.org -r $REALM_NAME
 kcadm.sh set-password -r $REALM_NAME --username $USER_NAME --new-password $USER_NAME-password
 echo "INFO: Created New user ${USER_NAME} in ${REALM_NAME}"
}

function delete_entities {
  if kcadm.sh get realms/${REALM_NAME} | grep -q ${REALM_NAME}; then
    kcadm.sh delete realms/${REALM_NAME} > /dev/null
    echo -e "Successfully Deleted the Client and Realm\n"
  fi
}

#main()
#setting default values
OPTIND=1
REALM_NAME=test-realm
CLIENT_ID=gatling
DELETE_ENTITIES='false'
USER_NAME=user-0

while getopts "k:r:u:c:d" arg; do
  case $arg in
    k) KEYCLOAK_HOME="$OPTARG";
        ;;
    r) REALM_NAME="$OPTARG";
        ;;
    c) CLIENT_ID="$OPTARG";
        ;;
    d) DELETE_ENTITIES=true;
        ;;
    u) USER_NAME="$OPTARG";
        ;;
    \?) echo "ERROR: Invalid option: -${OPTARG}" >&2
        usage
        ;;
  esac
done

#Handle missing opt args
if (( ${OPTIND} == 1 ))
then
  echo -e "ERROR: No Options Specified\n"
  usage
fi
shift $(( OPTIND -1 ))

#Setup the KCB client tool in the system PATH
set_kcb_in_path

#Create or Re-Create Client and Realm
if ${DELETE_ENTITIES}; then
 echo "INFO: deleting the Client and Realm"
 delete_entities
else
 echo "WARN: not deleting the Client and Realm"
fi

create_realm
create_service_enabled_client_assign_roles
create_oidc_client
create_user
