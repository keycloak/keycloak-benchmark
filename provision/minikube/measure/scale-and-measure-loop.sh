#!/bin/bash
set -e
SCRIPTPATH="$( cd -- "$(dirname "$0")" >/dev/null 2>&1 ; pwd -P )"

export timestamp="${timestamp:-$(date -uIseconds)}"

scale=${1:-1}
iterations=${2:-3}
memoryMeasurementTimes=${3:-""}
podDetectionTimeout=${4:-60}

echo "Storing test information."
mkdir -p "data/${timestamp}"
cat <<EOF > "data/${timestamp}/test.properties"
timestamp=${timestamp}
scale=${scale}
iterations=${iterations}
memoryMeasurementTimes=${memoryMeasurementTimes}
podDetectionTimeout=${podDetectionTimeout}
EOF

"$SCRIPTPATH/scale-keycloak.sh" 1 60
"$SCRIPTPATH/collect-sut-info.sh"

echo
echo "Starting the test loop."

for i in $(seq 1 ${iterations}); do

  echo 
  echo "Iteration $i of ${iterations}. $(date -uIseconds)"
  echo 

  "$SCRIPTPATH/scale-keycloak.sh" 0 60
  "$SCRIPTPATH/scale-keycloak.sh" ${scale} 0

  if [ -z "$memoryMeasurementTimes" ]; then 
    echo "Memory measurement times parameter not provided. Skipping memory measurement."
    sleep 5s
  else
    "$SCRIPTPATH/measure-memory-usage.sh" "$memoryMeasurementTimes"  $(( ${scale} * ${podDetectionTimeout} ))
  fi

  "$SCRIPTPATH/measure-timings.sh"

done

"$SCRIPTPATH/compute-stats.sh" "data/${timestamp}"
