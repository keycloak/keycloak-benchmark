#!/bin/bash
set -e

measurementTimes=${1:-"30 60 90 120"}
podDetectionTimeout=${2:-60}
measuredPods=""
export timestamp="${timestamp:-$(date -uIseconds)}"

function measurePodMemory {
  pod=$1
  podUID=$(kubectl -n keycloak get pods/${pod} -o jsonpath='{.metadata.uid}')
  echo "Measuring memory usage for pod '$pod', UID: ${podUID}."

  until ! kubectl -n keycloak get pods/${pod} | grep -i Terminating && [ "$(kubectl -n keycloak get pods/${pod} -o jsonpath='{.status.conditions[?(@.type=="Initialized")].status}' | tr '[:upper:]' '[:lower:]')" == "true" ]; do
    echo "Waiting for pod '$pod' to get initialized."
    sleep 1
  done

  csvFile="data/${timestamp}/${pod}/memory-usage-cgroups.csv"
  csvHeader="Pod UID"
  csvData="${podUID}"

  csv2File="data/${timestamp}/${pod}/memory-usage-rss.csv"
  csv2Header="Pod UID"
  csv2Data="${podUID}"

  timeInitialized=$(kubectl -n keycloak  get pods/${pod} -o jsonpath='{.status.conditions[?(@.type=="Initialized")].lastTransitionTime}')
  for measurementTime in $measurementTimes; do
    csvHeader+=",Memory at ${measurementTime} s (B)"
    csv2Header+=",RSS at ${measurementTime} s (B)"
    timeSinceInitialized=$(( $( date +%s ) - $( date -d "$timeInitialized" +%s ) ))
    podRemainingMeasurementTime=$(( $measurementTime - $timeSinceInitialized ))
    if [ ${podRemainingMeasurementTime} -lt 0 ]; then
      podRemainingMeasurementTime=0
      echo "Pod '${pod}' already exceeded measurement time of ${measurementTime} seconds after start. Skipping."
      csvData+=","
    else
      echo "Waiting ${podRemainingMeasurementTime} seconds for pod '${pod}' to be at ${measurementTime} seconds after start."
      sleep $podRemainingMeasurementTime
      memoryUsageInBytes=$(kubectl -n keycloak exec ${pod} -- cat /sys/fs/cgroup/memory/memory.usage_in_bytes)
      rssInBytes=$(kubectl -n keycloak exec keycloak-0 -- cat /proc/1/smaps | grep -i rss | awk '{sum+=$2} END {print (sum*1024)}')

      echo "Memory usage of pod '${pod}' at ${measurementTime} seconds after start is: ${memoryUsageInBytes} B."
      csvData+=",${memoryUsageInBytes}"
      csv2Data+=",${rssInBytes}"
    fi
  done

  if [ ! -f "$csvFile" ]; then 
    mkdir -p "data/${timestamp}/${pod}"
    echo "$csvHeader" > $csvFile
  fi
  echo "$csvData" >> $csvFile

  if [ ! -f "$csv2File" ]; then 
    mkdir -p "data/${timestamp}/${pod}"
    echo "$csv2Header" > $csv2File
  fi
  echo "$csv2Data" >> $csv2File

  echo "Measuring memory usage for pod '$pod' finished."
}

echo "Opening pod detection window for $podDetectionTimeout seconds."
t0=$(date +%s)
while [ "$(( $(date +%s) - $t0 ))" -lt $podDetectionTimeout ]; do
  for pod in $(kubectl -n keycloak get pods -o name | grep -oP "keycloak-[0-9]+"); do 
    if ! echo "$measuredPods" | grep -q "${pod}\ "; then
      echo "Detected new pod '$pod'."
      measuredPods="$measuredPods $pod "
      measurePodMemory $pod &
    fi
  done
  sleep 1
done
echo "Pod detection window closed after ${podDetectionTimeout} seconds."

wait
echo "Script finished."
