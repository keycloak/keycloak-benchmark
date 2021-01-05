# Keycloak Benchmark

This repository contains the necessary tools to run performances tests on a Keycloak server.

Section `Dataset` is to prefill the Keycloak/RHSSO DB with big amount of objects. Then section `Gatling` is to run the performance test itself
and generate some load. 

## Dataset

Dataset module is useful to populate target Keycloak/RHSSO server with many objects. For example:
- Populate your Keycloak realm with many users. This is useful when you want to run performance test with many concurrent user logins
and use different users for each login
- Populate your realm with many clients. This is useful for test service account logins and others...
- Populate your Keycloak server with many realms. Each realm will be filled with specified amount of roles, clients, groups and users, so this endpoint
might be sufficient for most of performance testing use-cases.

### Setup

The approach is to deploy the REST resource provider to the target Keycloak/RHSSO server. Then invoke REST requests to create many objects
of specified type.

**WARNING:** Approach of use Keycloak Admin REST API directly is slow in many environments and hence this approach is used to fill the data quickly. Make sure that 
in the production environment, this REST provider is not deployed (it is only for the purpose to quickly fill DB with the data, but should never be used in production). 


First is needed to build the provider, then to deploy it to the target Keycloak.

Build the project and deploy to the target Keycloak/RHSSO server

    mvn clean install
    cp target/keycloak-benchmark-dataset-*.jar $KEYCLOAK_HOME/standalone/deployments/
    
Instead of copying to `standalone/deployments`, the alternative is to deploy as a module

    mvn clean install
    export JAR_NAME=$(ls dataset/target/keycloak-benchmark-dataset-*.jar)
    $KEYCLOAK_HOME/bin/jboss-cli.sh --command="module add --name=org.keycloak.keycloak-benchmark --resources=$JAR_NAME --dependencies=org.keycloak.keycloak-common,org.keycloak.keycloak-core,org.keycloak.keycloak-server-spi,org.keycloak.keycloak-server-spi-private,org.keycloak.keycloak-services,org.keycloak.keycloak-model-infinispan,javax.ws.rs.api,org.jboss.resteasy.resteasy-jaxrs,org.jboss.logging,org.infinispan,org.infinispan.commons,org.infinispan.client.hotrod,org.infinispan.persistence.remote"

Then in the file `$KEYCLOAK_HOME/standalone/configuration/standalone.xml` add this additional line to the `providers` element of keycloak server subsystem:

    <provider>module:org.keycloak.keycloak-benchmark</provider>
    
See Keycloak server development guide for more details.

### Create many realms

You need to call this HTTP REST requests. This request is useful for create 10 realms. Each realm will contain specified amount of roles, clients, groups and users:

    http://localhost:8080/auth/realms/master/dataset/create-realms?count=10
    
### Create many clients
    
This is request to create 100 new clients in the realm `realm5` . Each client will have service account enabled and secret
like <<client_id>>-secret (For example `client-156-secret` in case of the client `client-156`):

    http://localhost:8080/auth/realms/master/dataset/create-clients?count=200&realm-name=realm5
 
### Create many users
   
This is request to create 500 new users in the `realm5`. Each user will have specified amount of roles, client roles and groups,
which were already created by `create-realms` endpoint. Each user will have password like <<Username>>-password . For example `user-156` will have password like
`user-156-password` :

    http://localhost:8080/auth/realms/master/dataset/create-users?count=1000&realm-name=realm5
    
### Remove many realms

To remove all realms with the default realm prefix `realm`

    http://localhost:8080/auth/realms/master/dataset/remove-realms?remove-all=true
    
You can use `realm-prefix` to change the default realm prefix. You can use parameters to remove all realms for example just from `foorealm5` to `foorealm15`

    localhost:8080/auth/realms/master/dataset/remove-realms?realm-prefix=foorealm&first-to-remove=5&last-to-remove=15          
    
### Change default parameters
    
For change the parameters, take a look at [DataSetConfig class](dataset/src/main/java/org/keycloak/benchmark/dataset/config/DatasetConfig.java)
to see available parameters and default values and which endpoint the particular parameter is applicable. For example to create realms with prefix `foo`
and with just 1000 hash iterations used for the password policy, you can use these parameters:

    http://localhost:8080/auth/realms/master/dataset/create-realms?count=10&realm-prefix=foo&password-hash-iterations=1000
    
The configuration is written to the server log when HTTP endpoint is triggered, so you can monitor the progress and what parameters were effectively applied.

Note that creation of new objects will automatically start from the next available index. For example when you trigger endpoint above
for creation many clients and you already had 230 clients in your DB (`client-0`, `client-1`, .. `client-229`), then your HTTP request
will start creating clients from `client-230` .

### Check last items of particular object

To see last created realm index

    http://localhost:8080/auth/realms/master/dataset/last-realm
    
To see last created client in given realm

    http://localhost:8080/auth/realms/master/dataset/last-client?realm-name=realm5
    
To see last created user in given realm

    http://localhost:8080/auth/realms/master/dataset/last-user?realm-name=realm5  


### Ability to clear remote caches

With the RHDG integration enabled, it may be useful to clear the content of the remote caches or see if particular item is available in the remote cache.
Those endpoints are not directly to dataset or performance tests, however they are generally useful for those cases. They are useful just with the
RHDG integration enabled.

Clear all the items in the specified remote cache - in this case cache `sessions`:
 
    http://localhost:8080/auth/realms/master/remote-cache/sessions/clear
    
See the count of items in the specified remote cache:

    http://localhost:8080/auth/realms/master/remote-cache/sessions/size
    
See if item with ID "123" exists in the cache:

    http://localhost:8080/auth/realms/master/remote-cache/sessions/contains/123
    
### Release

If you need to do changes in the "dataset" and then consume it for example from the Openshift pods, you may need the ability to push
your changes to the Keycloak and the release it. The info on how to release is in the [RELEASE.md](RELEASE.md).
## Gatling

Currently, performance tests are using Gatling as the runtime, where the simulations were extracted from the 
Keycloak Performance Test Suite and wrapped into a standalone tool that allows running tests using a CLI.

### Build

Build `keycloak-gatling` module: 

    mvn -f gatling/pom.xml clean install
    
As a result, you should have a ZIP and tar.gz file in the target folder.

    gatling/target/keycloak-gatling-${version}.[zip|tar.gz]
    
### Install

Extract the `keycloak-gatling-${version}.[zip|tar.gz]` file.

### Run

To start running tests:

    ./kcb.sh

By default, tests expect Keycloak running on http://localhost:8080/auth, and the default scenario is `keycloak.scenarion.authentication.ClientScret`.

To use a different server URL and scenario:

    ./kcb.sh --scenario=keycloak.scenario.authentication.AuthorizationCode --server-url=http://localhost:8080

#### Options

You can set different options to change how tests should be executed. For a complete list of the available options, see
[Config](gatling/src/main/java/org/keycloak/benchmark/Config.java).

### Report

Check reports at the `result` directory.

### Test Scenarios

These are the available test scenarios:

* `keycloak.scenario.authentication.AuthorizationCode`: Authorization Code Grant Type
* `keycloak.scenario.authentication.LoginUserPassword`: Browser Login (only Authorization Endpoint. After username+password login, there is no exchange of OAuth2 "code" for the tokens) 
* `keycloak.scenario.authentication.ClientSecret`: Client Secret (Client Credentials Grant)