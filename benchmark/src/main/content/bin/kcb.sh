#!/usr/bin/env bash

if [[ "$RUNNER_DEBUG" == "1" ]]; then
  set -x
fi

case "$(uname)" in
    CYGWIN*)
        CFILE=$(cygpath "$0")
        RESOLVED_NAME=$(readlink -f "$CFILE")
        ;;
    Darwin*)
        RESOLVED_NAME=$(readlink "$0")
        ;;
    FreeBSD)
        RESOLVED_NAME=$(readlink -f "$0")
        ;;
    Linux)
        RESOLVED_NAME=$(readlink -f "$0")
        ;;
esac

if [ "x$RESOLVED_NAME" = "x" ]; then
    RESOLVED_NAME="$0"
fi

GREP="grep"
DIRNAME=$(dirname "$RESOLVED_NAME")

# Default values
JAVA_OPTS="-server"
JAVA_OPTS="${JAVA_OPTS} -Xmx4G -XX:+HeapDumpOnOutOfMemoryError"

DEBUG_MODE="${DEBUG:-false}"
DEBUG_PORT="${DEBUG_PORT:-8787}"

CHAOS_MODE="${CHAOS_MODE:-false}"

CONFIG_ARGS=()
SERVER_OPTS=()

SCENARIO="keycloak.scenario.authentication.ClientSecret"

INCREMENT=32
MODE="single-run"

WORKLOAD_UNIT="users-per-sec"
CURRENT_WORKLOAD=1

MEASUREMENT=30

while [ "$#" -gt 0 ]
do
    case "$1" in
      --debug)
          DEBUG_MODE=true
          if [ -n "$2" ] && [ "$2" = "${2//-}" ]; then
              DEBUG_PORT=$2
              shift
          fi
          ;;
      --debug=*)
          DEBUG_MODE=true
          DEBUG_PORT=${1#*=}
          ;;
      --scenario=*)
          SCENARIO=${1#*=}
          ;;
      --concurrent-users=*)
          WORKLOAD_UNIT=concurrent-users
          CURRENT_WORKLOAD=${1#*=}
          ;;
      --users-per-sec=*)
          WORKLOAD_UNIT=users-per-sec
          CURRENT_WORKLOAD=${1#*=}
          ;;
      --measurement=*)
          MEASUREMENT=${1#*=}
          ;;
      --increment=*)
          MODE=incremental
          INCREMENT=${1#*=}
          ;;
      --chaos=*)
          CHAOS_MODE=true
          CHAOS_TIMEOUT=${1#*=}
          ;;
      --)
          shift
          break
          ;;
      *)
          if [[ $1 = --* || ! $1 =~ ^-D.* ]]; then
            CONFIG_ARGS+=("-D${1:2}")
          else
            SERVER_OPTS+=("$1")
          fi
          ;;
    esac
    shift
done

# Set debug settings if not already set
if [ "$DEBUG_MODE" = "true" ]; then
    DEBUG_OPT=$(echo "$JAVA_OPTS" | $GREP "\-agentlib:jdwp")
    if [ "x$DEBUG_OPT" = "x" ]; then
        JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,address=$DEBUG_PORT,server=y,suspend=y"
    else
        echo "DEBUG: Debug already enabled in JAVA_OPTS, ignoring --debug argument."
    fi
fi

CLASSPATH_OPTS="$DIRNAME/../lib/*"

declare -A RESULT_CACHE

rewrite_output() {
    sed -u 's|Please open the following file: |Please open the following file://|g' < /dev/stdin
}

run_benchmark_with_workload() {
  if [[ -v RESULT_CACHE[$2] ]]; then
      echo "INFO: Keycloak benchmark was already running for $1=$2 with result ${RESULT_CACHE[$2]}."
      return "${RESULT_CACHE[$2]}"
  fi
  local OUTPUT_DIR="${4:-"$DIRNAME/../results/"}"
  echo "INFO: Running benchmark with $1=$2, result output will be available in: $OUTPUT_DIR"
  mkdir -p "$OUTPUT_DIR"
  GRAFANA_FROM_DATE_UNIX_MS=$(date +%s%3N)
  DATE_START_UNIX=$(date +%s)
  DATE_START_ISO=$(date --iso-8601=seconds)
  DATE_START_ISO_COMPRESSED=$(date '+%Y%m%d-%H%M%S')
  if [ "$MODE" = "incremental" ]; then
    java $JAVA_OPTS "${SERVER_OPTS[@]}" "${CONFIG_ARGS[@]}" "-D$1=$2" "-Dmeasurement=${3:-30}" -cp $CLASSPATH_OPTS io.gatling.app.Gatling -rf "$OUTPUT_DIR" -s $SCENARIO > "$OUTPUT_DIR/gatling.log" 2>&1
  else
    java $JAVA_OPTS "${SERVER_OPTS[@]}" "${CONFIG_ARGS[@]}" "-D$1=$2" "-Dmeasurement=${3:-30}" -cp $CLASSPATH_OPTS io.gatling.app.Gatling -rf "$OUTPUT_DIR" -s $SCENARIO | rewrite_output 2>&1 | tee "$OUTPUT_DIR/gatling.log"
  fi
  EXIT_RESULT=$?
  # don't include URLs or password information into the configuration recorded
  CONFIG_ARGS_CLEAN=()
  for index in "${!CONFIG_ARGS[@]}" ; do [[ "${CONFIG_ARGS[$index]}" =~ .*(url|pass).* ]] || CONFIG_ARGS_CLEAN+=(${CONFIG_ARGS[$index]}) ; done
  OUTPUT_FOLDER=$OUTPUT_DIR/$(ls $OUTPUT_DIR -Art | grep -- -20 | tail -1)
  DATE_END_UNIX=$(date +%s)
  DATE_END_ISO=$(date --iso-8601=seconds)
  GRAFANA_TO_DATE_UNIX_MS=$(date +%s%3N)
  SNAP_GRAFANA_TIME_WINDOW="from=${GRAFANA_FROM_DATE_UNIX_MS}&to=${GRAFANA_TO_DATE_UNIX_MS}"
  jq '{ "grafana_output": { "stats": . } }' $OUTPUT_FOLDER/js/stats.json > $OUTPUT_FOLDER/result_grafana_stats.json
  UUID=$(uuidgen)
  jq '.'  > $OUTPUT_FOLDER/result_grafana_inputs.json <<EOF
  {
    "uuid": "${UUID}",
    "name": "Scenario '${SCENARIO}' with $2 $1",
    "grafana_input": {
      "start": {
        "epoch_seconds": ${DATE_START_UNIX},
        "iso": "${DATE_START_ISO}"
      },
      "end": {
        "epoch_seconds": ${DATE_END_UNIX},
        "iso": "${DATE_END_ISO}"
      },
      "input": {
        "scenario": "${SCENARIO}",
        "snap_grafana_time_window": "${SNAP_GRAFANA_TIME_WINDOW}",
        "unit": "$1",
        "value": $2,
        "config": "${CONFIG_ARGS_CLEAN[@]}"
      }
    }
  }
EOF
  if [[ "${SUT_DESCRIBE}" != "" ]]; then
    ${SUT_DESCRIBE} | jq '{ "system_under_test": . }' > ${OUTPUT_FOLDER}/result_sut.json
  fi
  jq -s add ${OUTPUT_FOLDER}/result_*.json > ${OUTPUT_FOLDER}/result-${DATE_START_ISO_COMPRESSED}-${UUID}.json
  return ${EXIT_RESULT}
}

if [ "$CHAOS_MODE" = "true" ]; then
    echo "INFO: Running benchmark with chaos mode, logs output will be available in: $LOGS_DIR"
    LOGS_DIR="$DIRNAME/../results/logs/"

    mkdir -p "$LOGS_DIR"
    timeout "${CHAOS_TIMEOUT}" bash bin/kc-chaos.sh "${LOGS_DIR}" 2>&1 | tee "${LOGS_DIR}/kc-chaos.log" &
fi

if [ "$MODE" = "incremental" ]; then
  echo "INFO: Running benchmark in incremental mode."
  MAX_ATTEMPTS=100
  ATTEMPT=0

  trap printout SIGINT
  printout() {
      echo ""
      echo "INFO: Finished with $WORKLOAD_UNIT=$CURRENT_WORKLOAD."
      exit
  }

  RESULT_ROOT_DIR="$DIRNAME/../results/$MODE-$(date '+%Y%m%d%H%M%S')"
  mkdir -p $RESULT_ROOT_DIR
  RESULT_ROOT_DIR=$(realpath ${RESULT_ROOT_DIR})

  #Incremental run is expected to do a warm up run to setup the system for the subsequent Incremental runs, you can ignore this run's result.
  echo "INFO: Running warm-up phase."
  run_benchmark_with_workload "$WORKLOAD_UNIT" "$CURRENT_WORKLOAD" "$MEASUREMENT" "$RESULT_ROOT_DIR/$WORKLOAD_UNIT-$CURRENT_WORKLOAD-WARM-UP"
  echo "INFO: Finished Warm-Up phase, for incremental run."

  while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    # Check for invalid workload
    if [ $CURRENT_WORKLOAD -lt 1 ]; then
      echo "ERROR: Invalid state for $SCENARIO with $WORKLOAD_UNIT=$LAST_SUCCESSFUL_WORKLOAD."
      exit 1
    fi

    ATTEMPT=$[ATTEMPT + 1]

    run_benchmark_with_workload "$WORKLOAD_UNIT" "$CURRENT_WORKLOAD" "$MEASUREMENT" "$RESULT_ROOT_DIR/$WORKLOAD_UNIT-$CURRENT_WORKLOAD"

    RESULT_CACHE[$CURRENT_WORKLOAD]=$?

    if [ ${RESULT_CACHE[$CURRENT_WORKLOAD]} -ne 0 ]; then
      echo "INFO: Keycloak benchmark failed for $WORKLOAD_UNIT=$CURRENT_WORKLOAD"
      LAST_SUCCESSFUL_WORKLOAD=$((CURRENT_WORKLOAD - INCREMENT))
      if [ $((RESULT_CACHE[$LAST_SUCCESSFUL_WORKLOAD])) -ne 0 ]; then
        echo "ERROR: Invalid state. Last successful workload $LAST_SUCCESSFUL_WORKLOAD was not successful."
        exit 1
      fi

      echo "INFO: Last Successful workload for scenario $SCENARIO is $WORKLOAD_UNIT=$LAST_SUCCESSFUL_WORKLOAD."
      if [ $INCREMENT -eq 1 ]; then
        ln -s $RESULT_ROOT_DIR/$WORKLOAD_UNIT-$LAST_SUCCESSFUL_WORKLOAD $RESULT_ROOT_DIR/last-successful
        if [[ "${GITHUB_OUTPUT}" != "" ]]; then
          OUTPUT_FOLDER=$RESULT_ROOT_DIR/last-successful/$(ls $RESULT_ROOT_DIR/last-successful -Art | grep -- -20 | tail -1)
          echo "kcb_result=$OUTPUT_FOLDER/result-*.json" >> "${GITHUB_OUTPUT}"
        fi
        echo "INFO: Reached the limit for scenario $SCENARIO with $WORKLOAD_UNIT=$LAST_SUCCESSFUL_WORKLOAD."
        exit
      fi

      # Reset workload to last successful value and decrease increment
      CURRENT_WORKLOAD=$((CURRENT_WORKLOAD - INCREMENT))
      INCREMENT=$((INCREMENT / 2))
    fi

    CURRENT_WORKLOAD=$((CURRENT_WORKLOAD + INCREMENT))
  done

  if [ $ATTEMPT -eq $MAX_ATTEMPTS ]; then
    echo "INFO: Reached maximum attempts and all attempts succeeded."
  fi

else
  echo "INFO: Running benchmark in single-run mode."
  run_benchmark_with_workload $WORKLOAD_UNIT $CURRENT_WORKLOAD $MEASUREMENT
  if [[ "${GITHUB_OUTPUT}" != "" ]]; then
    OUTPUT_FOLDER=$DIRNAME/../results/$(ls $DIRNAME/../results -Art | grep -- -20 | tail -1)
    OUTPUT_FOLDER=$(realpath ${OUTPUT_FOLDER})
    echo "kcb_result=$OUTPUT_FOLDER/result-*.json" >> "${GITHUB_OUTPUT}"
  fi
  exit
fi

if [ "$CHAOS_MODE" = "true" ]; then
    : ${PROJECT:="runner-keycloak"}
    echo "INFO: Collecting logs at the end of the Chaos benchmark run"
    PODS=$(kubectl -n "${PROJECT}" -o 'jsonpath={.items[*].metadata.name}' get pods -l app=keycloak | tr " " "\n")
    for POD in $PODS; do
      kubectl logs -n "${PROJECT}" "${POD}" > "$LOGS_DIR/End-of-run-${POD}.log" 2>&1
      kubectl describe -n "${PROJECT}" pod "${POD}" > "$LOGS_DIR/End-of-run-${POD}-complete-resource.log" 2>&1
    done
    kubectl top -n "${PROJECT}" pod -l app=keycloak --sum=true > "$LOGS_DIR/End-of-run-top.log" 2>&1
    kubectl get pods -n "${PROJECT}" -l app=keycloak -o wide
fi
