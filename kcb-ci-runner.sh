#!/bin/bash
set -euxo pipefail

export REPORTS_HOME=$USER_HOME/reports
MINIKUBE_HOME=$PROJECT_HOME/provision/minikube

echo "INFO: reset the keycloak based on the parametrized input"
export RESET_KEYCLOAK=${RESET_KEYCLOAK:-true}
export KEYCLOAK_STORAGE=${KEYCLOAK_STORAGE:-"JPA-PostgreSQL"}
if $RESET_KEYCLOAK; then
  cd $MINIKUBE_HOME
  if [ "$KEYCLOAK_STORAGE" = "JPA-PostgreSQL" ]; then
    task reset-keycloak KC_DATABASE="postgres"
  else
    echo "Invalid KEYCLOAK_STORAGE: \"$KEYCLOAK_STORAGE\"."
    exit 1
  fi
  echo "INFO: task reset-keycloak has been run with Storage as $KEYCLOAK_STORAGE"
  cd $PROJECT_HOME
else
  echo "INFO: Keycloak DB is not reset for this run"
fi

echo "INFO: set environment variables with parametrized inputs or defaults"
export MINIKUBE_IP=$(minikube ip)
export GRAPHITE_TCP_ADDR=$MINIKUBE_IP
export KC_SERVER_URL=${KC_SERVER_URL:-"https://keycloak-keycloak.$MINIKUBE_IP.nip.io/"}
export SCENARIO=${SCENARIO}
export CLIENT_SECRET=setup-for-benchmark
export RAMPUP=${RAMPUP}
export MEASUREMENT=${MEASUREMENT}
export RAMPDOWN=${RAMPDOWN}
export USER_THINK_TIME=${USER_THINK_TIME}
export SLA_ERROR_PERCENTAGE=${SLA_ERROR_PERCENTAGE:-0}
export SLA_MEAN_RESPONSE_TIME=${SLA_MEAN_RESPONSE_TIME:-400}

export WORKLOAD_MODEL=${WORKLOAD_MODEL}
if [ "$WORKLOAD_MODEL" = "open" ]; then
  export WORKLOAD_PARAM="--users-per-sec=$WORKLOAD_UNIT"
elif [ "$WORKLOAD_MODEL" = "closed" ]; then
  export WORKLOAD_PARAM="--concurrent-users=$WORKLOAD_UNIT"
else
  echo "Invalid WORKLOAD_MODEL: \"$WORKLOAD_MODEL\". Valid values: \"open\" or \"closed\"."
  exit 1
fi

if [[ "$SCENARIO" == *"Realm"* ]]; then
  export WORKLOAD_PARAM="$WORKLOAD_PARAM --admin-username=admin --admin-password=admin"
elif [[ "$SCENARIO" == *"authentication"* ]]; then
  export WORKLOAD_PARAM="$WORKLOAD_PARAM --realm-name=test-realm"
else
  export WORKLOAD_PARAM="$WORKLOAD_PARAM --client-secret=$CLIENT_SECRET --realm-name=test-realm"
fi

if [[ -n "$LOGOUT_PERCENTAGE" ]]; then
  export WORKLOAD_PARAM="$WORKLOAD_PARAM --logout-percentage=$LOGOUT_PERCENTAGE"
fi

#pull latest project and build
echo "Building '$GIT_PROJECT_NAME' branch 'main'"
git diff
git pull
export KCB_REVISION=$(git rev-parse --short HEAD)
echo "KCB_REVISION: $KCB_REVISION"
mvn -B -f benchmark clean install -DskipTests
KCB_ZIP=$(find benchmark -name keycloak-benchmark-*.zip)
KCB_VERSION=$(echo $KCB_ZIP | sed -n "s/.*keycloak-benchmark-\(\S*\).zip$/\1/p")
echo "KCB_VERSION: $KCB_VERSION"
if [ -d "keycloak-benchmark-$KCB_VERSION" ]; then rm -rf "keycloak-benchmark-$KCB_VERSION"; fi
echo "Installing Keycloak Benchmark"
unzip -qo $KCB_ZIP

KCB_HOME=$PROJECT_HOME/keycloak-benchmark-$KCB_VERSION
cd $KCB_HOME

#Remove old results before the run
rm -rf results/; mkdir -p results/
export RESULTS_HOME=$KCB_HOME/results
export REPORT_DIR=$SCENARIO-$KEYCLOAK_STORAGE-$(date -u "+%Y%m%dT%H%M%S%Z")

#Execute the keycloak benchmark and start simulation
echo "INFO: Running kcb.sh"

./bin/kcb.sh --scenario=keycloak.scenario.$SCENARIO \
--server-url=$KC_SERVER_URL \
$WORKLOAD_PARAM \
--ramp-up=$RAMPUP --measurement=$MEASUREMENT \
--ramp-down=$RAMPDOWN --user-think-time=$USER_THINK_TIME || true

echo "INFO: Archive simulation.log and gatling HTML reports, for the run"
cd $RESULTS_HOME
mkdir -p $REPORTS_HOME/$REPORT_DIR/SimulationLogs
cp $RESULTS_HOME/*/simulation.log $REPORTS_HOME/$REPORT_DIR/SimulationLogs
zip -qr $REPORTS_HOME/$REPORT_DIR/$REPORT_DIR.zip .
cd $REPORTS_HOME/$REPORT_DIR && unzip $REPORTS_HOME/$REPORT_DIR/$REPORT_DIR.zip
touch load_run_parameters.txt && chmod 0755 load_run_parameters.txt
echo -e "WORKLOAD_PARAM: $WORKLOAD_PARAM\nRAMP_UP: $RAMPUP\nRAMP_DOWN: $RAMPDOWN\nMEASUREMENT: $MEASUREMENT\nUSER_THINKTIME: $USER_THINK_TIME\nKCB_VERSION: $KCB_VERSION\nKCB_REVISION: $KCB_REVISION" >> load_run_parameters.txt

#Generate Custom Report
sh $PROJECT_HOME/benchmark/src/main/content/bin/generate-custom-report.sh -v 6.0 -s "$REPORTS_HOME/$REPORT_DIR/SimulationLogs/simulation.log" -d "$REPORTS_HOME/$REPORT_DIR/CustomReport"
echo "INFO: generated the report and it should be available at http://$(hostname)/$REPORT_DIR"

cd $REPORTS_HOME/$REPORT_DIR && zip -qur $REPORT_DIR.zip CustomReport
cp -a $REPORTS_HOME/$REPORT_DIR/* $JENKINS_WORKSPACE/archives/
echo "INFO: End of the Run"
