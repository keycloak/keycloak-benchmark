#!/bin/bash 
set -e

export REPORTS_HOME=$USER_HOME/reports
export MINIKUBE_HOME=$PROJECT_HOME/provision/minikube

echo "INFO: reset the keycloak based on the parametrized input"
export RESET_KEYCLOAK=${RESET_KEYCLOAK:-true}
if $RESET_KEYCLOAK; then
  cd $MINIKUBE_HOME
  task reset-keycloak
  echo "INFO: task reset-keycloak has been run"
  cd $PROJECT_HOME
else
  echo "INFO: Keycloak DB is not reset for this run"
fi

echo "INFO: set environment variables with parametrized inputs or defaults"
export GRAPHITE_TCP_ADDR=$(minikube ip)
export KC_SERVER_URL=${KC_SERVER_URL:-"https://keycloak.$GRAPHITE_TCP_ADDR.nip.io/"}
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

#pull latest project and build
echo "Building '$KCB_REPOSITORY' branch 'main'"
git diff
git pull
export KCB_REVISION=$(git rev-parse --short HEAD)
echo "KCB_REVISION: $KCB_REVISION"
mvn -f benchmark clean install -DskipTests
KCB_ZIP=$(find benchmark -name keycloak-benchmark-*.zip)
KCB_VERSION=$(echo $KCB_ZIP | sed -n "s/.*keycloak-benchmark-\(\S*\).zip$/\1/p")
echo "KCB_VERSION: $KCB_VERSION"
if [ -d "keycloak-benchmark-$KCB_VERSION" ]; then rm -rf "keycloak-benchmark-$KCB_VERSION"; fi
echo "Unzipping"
unzip -qo $KCB_ZIP

KCB_HOME=$PROJECT_HOME/keycloak-benchmark-$KCB_VERSION
cd $KCB_HOME

#Remove old results before the run
rm -rf results/; mkdir -p results/
export RESULTS_HOME=$KCB_HOME/results
export REPORT_DIR=$SCENARIO-$(date -u "+%Y-%m-%dT%H-%M-%S%Z")

#Execute the keycloak benchmark and start simulation
echo "INFO: Running kcb.sh --scenario=keycloak.scenario.$SCENARIO --server-url=$KC_SERVER_URL --client-secret=$CLIENT_SECRET $WORKLOAD_PARAM --ramp-up=$RAMPUP --measurement=$MEASUREMENT --ramp-down=$RAMPDOWN --user-think-time=$USER_THINK_TIME"

./bin/kcb.sh --scenario=keycloak.scenario.$SCENARIO \
--server-url=$KC_SERVER_URL --client-secret=$CLIENT_SECRET \
$WORKLOAD_PARAM \
--ramp-up=$RAMPUP --measurement=$MEASUREMENT \
--ramp-down=$RAMPDOWN --user-think-time=$USER_THINK_TIME

echo "INFO: Archive simulation.log and gatling HTML reports, for the run"
cd $RESULTS_HOME
mkdir -p $REPORTS_HOME/$REPORT_DIR/SimulationLogs
zip -qr $REPORTS_HOME/$REPORT_DIR/$REPORT_DIR.zip . 
cp $RESULTS_HOME/*/simulation.log $REPORTS_HOME/$REPORT_DIR/SimulationLogs

#Generate Report
cd $PROJECT_HOME/benchmark
./generate-custom-report.sh -v 6.0 -s "$REPORTS_HOME/$REPORT_DIR/SimulationLogs/simulation.log" -d "$REPORTS_HOME/$REPORT_DIR/"
echo "INFO: generated the report and it should be available at $REPORTS_HOME/$REPORT_DIR/"