# Keycloak Benchmark

This repository contains the necessary tools to run performances tests on a Keycloak server.

## Gatling

Currently, performance tests are using Gatling as the runtime, where the simulations were extracted from the 
Keycloak Performance Test Suite and wrapped into a standalone tool that allows running tests using a CLI.

## Build

Build `keycloak-gatling` module: 

    mvn -f gatling/pom.xml clean install
    
As a result, you should have a ZIP and tar.gz file in the target folder.

    gatling/target/keycloak-gatling-${version}.[zip|tar.gz]
    
## Install

Extract the `keycloak-gatling-${version}.[zip|tar.gz]` file.

## Run

To start running tests:

    ./run.sh --scenario=keycloak.OIDCLoginAndLogoutSimulation --realm-name=test --username=test --user-password=test --users-per-sec=10

Alternatively, you can run tests using different realms and users:

    ./run.sh --scenario=keycloak.OIDCLoginAndLogoutSimulation --realms=599 --users-per-realm=199
    
By default, tests expect Keycloak running at http://localhost:8080. To run using a different URL pass the
`--server-url=<keycloak_url>` option. For instance, `--server-url=https://keycloak.org/auth`. 
    
