#!/bin/bash
set -euxo pipefail
cd java-instrumentation-tool
mvn clean package
cd ..
FILE=tlscheckdisable-agent.jar
$JAVA_HOME/bin/java -jar java-instrumentation-tool/target/java-instrumentation-tool-*.jar \
 tlscheckdisable.txt ${FILE}
echo agent: ${FILE}
