function error_and_exit() {
    echo "Error: ${@:2}"
    exit "${1}"
}

# required!
[ -z "${CLUSTER_1}" ] && error_and_exit 1 "CLUSTER_1 is required. CLUSTER_1 is the name of the first ROSA cluster."
[ -z "${NS_1}" ] && error_and_exit 3 "NS_1 is required. NS_1 is the namespace to install Infinispan in the first ROSA cluster"

KUBECONFIG_1=${KUBECONFIG_1:-"./kubecfg_1"}
KUBECONFIG_2=${KUBECONFIG_2:-"./kubecfg_2"}


function rosa_oc_login() {
    local kubecfg="${1}"
    local cluster="${2}"

    # if file exists, assume oc login is done
    [ -f "${kubecfg}" ] || KUBECONFIG="${kubecfg}" CLUSTER_NAME="${cluster}" ${WD}/../aws/rosa_oc_login.sh
}
