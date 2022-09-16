#!/bin/bash
set -e
set -o pipefail
# set -x

#Functions
function usage {
  echo -e "Generate a CSV or HTML (differential or trendline) report from existing gatling simulation.log files \n"
  echo -e "Usage: $(basename $0) -s SIMULATION_LOGS [-v GATLING_REPORT_VERSION] [-d TARGET_DIR] [-t TEMPLATE] [-c] \nOptions:" 2>&1
  echo -e "-v gatling-report release version to use, default ${GATLING_REPORT_VERSION} \n   refer here for latest versions https://maven-eu.nuxeo.org/nexus/#nexus-search;quick~gatling-report"
  echo -e '-s simulation.log files\n   hint: provide multiple locations in a double quotes'
  echo "-d destination directory where the report would be stored, default ${TARGET_DIR}"
  echo '-t custom mustache template path'
  echo '-c generate a csv report: default is to generate an HTML report'
  exit 1
}

function pull_gatling_report_jar {
  wget -q --no-directories --accept=jar https://maven-eu.nuxeo.org/nexus/service/local/repositories/vendor-releases/content/org/nuxeo/tools/gatling-report/${GATLING_REPORT_VERSION}/gatling-report-${GATLING_REPORT_VERSION}-capsule-fat.jar -O gatling-report-${GATLING_REPORT_VERSION}-fat.jar
}

function delete_gatling_report_jar {
  rm gatling-report-${GATLING_REPORT_VERSION}-fat.jar
}

function generate_report {
  if ${GENERATE_CSV_REPORT}; then
    echo "GENERATING THE CSV REPORT"
    java -jar gatling-report-${GATLING_REPORT_VERSION}-fat.jar $SRC_DIR > $TARGET_DIR/gatling-report-$(date +%s).csv
  else
    if ${USE_TEMPLATE}; then
      echo "GENERATING THE HTML REPORT"
      java -jar gatling-report-${GATLING_REPORT_VERSION}-fat.jar --template $TEMPLATE_PATH $SRC_DIR -o $TARGET_DIR -f  
    else
      echo "GENERATING THE HTML REPORT"
      java -jar gatling-report-${GATLING_REPORT_VERSION}-fat.jar $SRC_DIR -o $TARGET_DIR -f
    fi
  fi
}

#main()
#setting default values
GATLING_REPORT_VERSION=6.0
GENERATE_CSV_REPORT=false
USE_TEMPLATE=false
TARGET_DIR=/home/$USER/reports/

while getopts "hv:s:d:t:c" arg; do
  case $arg in
    h) usage
        ;;
    v) GATLING_REPORT_VERSION="$OPTARG";
        ;;
    s) SRC_DIR="$OPTARG";
        ;;
    d) TARGET_DIR="$OPTARG";
        ;;
    t) TEMPLATE_PATH="$OPTARG";USE_TEMPLATE=true;
        ;;
    c) GENERATE_CSV_REPORT=true;
        ;;
    \?) echo "ERROR: Invalid option provided" >&2
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

#call the functions
pull_gatling_report_jar
generate_report
delete_gatling_report_jar