#!/usr/bin/env bash
set -e

dataDir=${1:-"data"}

for csvFile in $(find "${dataDir}" -path "**/keycloak-*/**" -name memory-usage-cgroups.csv -o -name memory-usage-rss.csv -o -name startup-times.csv ); do
  csvStatisticsFile="${csvFile%.*}-statistics.csv"
  if [ ! -f "${csvStatisticsFile}" ]; then
    echo "Computing statistics for: ${csvFile}"
    awk -v 'FF=2' -f stats.awk "${csvFile}" > "${csvStatisticsFile}"
  fi

  csvJenkinsPlotFile="${csvFile%.*}-jenkins-plot.csv"
  if [ ! -f "${csvJenkinsPlotFile}" ]; then
    case "$(basename -s .csv $csvFile)" in
      startup-times) 
        echo "Extracting Jenkins plot data into: ${csvJenkinsPlotFile}"
        cat "$csvStatisticsFile" | awk 'NR==1 || NR==3 { print $3,$5,$6 }' FS=',' OFS=',' > "${csvJenkinsPlotFile}"
      ;;
      memory-usage-rss|memory-usage-cgroups)
        echo "Extracting Jenkins plot data into: ${csvJenkinsPlotFile}"
        cat "$csvStatisticsFile" | awk 'NR==1 || NR==3 { print $NF }' FS=',' > "${csvJenkinsPlotFile}"
      ;;
    esac
  fi
done
