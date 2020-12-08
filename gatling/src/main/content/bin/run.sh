#!/bin/bash

case "`uname`" in
    CYGWIN*)
        CFILE = `cygpath "$0"`
        RESOLVED_NAME=`readlink -f "$CFILE"`
        ;;
    Darwin*)
        RESOLVED_NAME=`readlink "$0"`
        ;;
    FreeBSD)
        RESOLVED_NAME=`readlink -f "$0"`
        ;;
    Linux)
        RESOLVED_NAME=`readlink -f "$0"`
        ;;
esac

if [ "x$RESOLVED_NAME" = "x" ]; then
    RESOLVED_NAME="$0"
fi

GREP="grep"
DIRNAME=`dirname "$RESOLVED_NAME"`

JAVA_OPTS="-server"
JAVA_OPTS="${DEFAULT_JAVA_OPTS} -Xmx1G -XX:+HeapDumpOnOutOfMemoryError"
JAVA_OPTS="${DEFAULT_JAVA_OPTS} -XX:+UseG1GC -XX:+ParallelRefProcEnabled"
JAVA_OPTS="${DEFAULT_JAVA_OPTS} -XX:MaxInlineLevel=20 -XX:MaxTrivialSize=12 -XX:-UseBiasedLocking"

DEBUG_MODE="${DEBUG:-false}"
DEBUG_PORT="${DEBUG_PORT:-8787}"

CONFIG_ARGS=${CONFIG_ARGS:-""}

SCENARIO="keycloak.ClientCredentialsSimulation"

while [ "$#" -gt 0 ]
do
    case "$1" in
      --debug)
          DEBUG_MODE=true
          if [ -n "$2" ] && [ "$2" = `echo "$2" | sed 's/-//'` ]; then
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
      --server-url=*)
          CONFIG_ARGS="$CONFIG_ARGS -Dkeycloak.server.uris=${1#*=}"
          ;;
      --)
          shift
          break
          ;;
      *)
          if [[ $1 = --* || ! $1 =~ ^-.* ]]; then
            CONFIG_ARGS="$CONFIG_ARGS -D${1:2}"
          else
            SERVER_OPTS="$SERVER_OPTS $1"
          fi
          ;;
    esac
    shift
done

# Set debug settings if not already set
if [ "$DEBUG_MODE" = "true" ]; then
    DEBUG_OPT=`echo $JAVA_OPTS | $GREP "\-agentlib:jdwp"`
    if [ "x$DEBUG_OPT" = "x" ]; then
        JAVA_OPTS="$JAVA_OPTS -agentlib:jdwp=transport=dt_socket,address=$DEBUG_PORT,server=y,suspend=y"
    else
        echo "Debug already enabled in JAVA_OPTS, ignoring --debug argument"
    fi
fi

CLASSPATH_OPTS="$DIRNAME/../lib/*"

echo $CONFIG_ARGS

exec java $JAVA_OPTS $CONFIG_ARGS -cp $CLASSPATH_OPTS io.gatling.app.Gatling -bf $DIRNAME -rf $DIRNAME/results -s $SCENARIO