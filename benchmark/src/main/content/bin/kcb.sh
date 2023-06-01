#!/bin/bash

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
JAVA_OPTS="${JAVA_OPTS} -Xmx1G -XX:+HeapDumpOnOutOfMemoryError"

DEBUG_MODE="${DEBUG:-false}"
DEBUG_PORT="${DEBUG_PORT:-8787}"

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
          CURRENT_WORKLOAD=(${1#*=})
          ;;
      --users-per-sec=*)
          WORKLOAD_UNIT=users-per-sec
          CURRENT_WORKLOAD=(${1#*=})
          ;;
      --ramp-up=*)
          RAMP_UP=${1#*=}
          ;;
      --measurement=*)
          MEASUREMENT=${1#*=}
          ;;
      --increment=*)
          MODE=incremental
          INCREMENT=${1#*=}
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

run_benchmark_with_workload() {
  if [[ -v RESULT_CACHE[$2] ]]; then
      echo "INFO: Keycloak benchmark was already running for $1=$2 with result ${RESULT_CACHE[$2]}."
      return "${RESULT_CACHE[$2]}"
  fi
  local OUTPUT_DIR="${4:-"$DIRNAME/../results/"}"
  echo "INFO: Running benchmark with $1=$2, result output will be available in: $OUTPUT_DIR."
  mkdir -p "$OUTPUT_DIR"
  java $JAVA_OPTS "${SERVER_OPTS[@]}" "${CONFIG_ARGS[@]}" "-D$1=$2" "-Dmeasurement=${3:-30}" -cp $CLASSPATH_OPTS io.gatling.app.Gatling -bf $DIRNAME -rf "$OUTPUT_DIR" -s $SCENARIO > "$OUTPUT_DIR/gatling.log" 2>&1
}

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

  if [ -n $RAMP_UP ]; then
    echo "INFO: Running ramp-up phase."
    CONFIG_ARGS+=("-Dramp-up=1")

    run_benchmark_with_workload "$WORKLOAD_UNIT" "$CURRENT_WORKLOAD" "$RAMP_UP" "$RESULT_ROOT_DIR/$WORKLOAD_UNIT-$CURRENT_WORKLOAD-RAMP-UP"

    echo "INFO: Finished ramp-up phase."
  fi

  while [ $ATTEMPT -lt $MAX_ATTEMPTS ]; do
    # Check for invalid workload
    if [ $CURRENT_WORKLOAD -lt 1 ]; then
      echo "ERROR: Invalid state for $SCENARIO with $WORKLOAD_UNIT=$LAST_SUCCESSFUL_WORKLOAD."
      exit 1
    fi

    ((ATTEMPT++))

    run_benchmark_with_workload "$WORKLOAD_UNIT" "$CURRENT_WORKLOAD" "$MEASUREMENT" "$RESULT_ROOT_DIR/$WORKLOAD_UNIT-$CURRENT_WORKLOAD"

    RESULT_CACHE[$CURRENT_WORKLOAD]=$?

    if [ ${RESULT_CACHE[$CURRENT_WORKLOAD]} -ne 0 ]; then
      echo "INFO: Keycloak benchmark failed for $WORKLOAD_UNIT=$CURRENT_WORKLOAD"
      LAST_SUCCESSFUL_WORKLOAD=$((CURRENT_WORKLOAD - INCREMENT))
      if [ $RESULT_CACHE[$LAST_SUCCESSFUL_WORKLOAD] -ne 0 ]; then
        echo "ERROR: Invalid state. Last successful workload $LAST_SUCCESSFUL_WORKLOAD was not successful."
        exit 1
      fi

      echo "INFO: Last Successful workload for scenario $SCENARIO is $WORKLOAD_UNIT=$LAST_SUCCESSFUL_WORKLOAD."
      if [ $INCREMENT -eq 1 ]; then
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
  if [ -n $RAMP_UP ]; then
    echo "INFO: Running benchmark with ramp-up."
    CONFIG_ARGS+=("-Dramp-up=${$RAMP_UP}")
  fi

  run_benchmark_with_workload $WORKLOAD_UNIT $CURRENT_WORKLOAD $MEASUREMENT
  exit
fi
