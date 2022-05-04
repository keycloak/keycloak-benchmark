## Benchmark

Currently, performance tests are using Gatling as the runtime, where the simulations were extracted from the 
Keycloak Performance Test Suite and wrapped into a standalone tool that allows running tests using a CLI.

Note: this tool assumes you have a running keycloak setup,
and if you want to load the keycloak server with some seed data, please see : [Dataset Module Instructions](../README.md)
### Build

Build `keycloak-benchmark` module: 

    mvn -f benchmark/pom.xml clean install
    
As a result, you should have a ZIP and tar.gz file in the target folder.

    benchmark/target/keycloak-benchmark-${version}.[zip|tar.gz]
    
### Install

Extract the `keycloak-benchmark-${version}.[zip|tar.gz]` file.

### Prepare keycloak for testing

Before running tests, make sure realms are configured as follows:

* Realms must have `User Registration` setting enabled.

Some scenarios (`CreateDeleteClients`, and `CrawlUsers`, `CreateRealms`, `CreateDeleteClientScopes`) require a service account with the clientId `gatling`:

* select the realm that is used for testing
* create a client  with the name `gatling`
   * set access type to `confidential`
   * check `Service Account Enabled`
   * enter a valid redirect uri (e.g. `http://localhost`)  
   * click save
* Change to the tab `Sevice Account Roles`
   * select for `realm-management` in the `Client Roles` listbox
   * assign the roles `manage-clients` and `view-users`
* the client secret to be passed to the tests can be copied from the `Credentials` tab

#### Scenario `keycloak.scenario.admin.CreateRealms`

This scenario requires the following Client settings, in addition to the requirements above:

* In to the `Scopes` tab
    * Set `Full Scope Allowed` to `Off`
    * Add role `create-realm` in the `Realm Roles` section
* In the `Service Account Roles` tab
    * assign the roles `create-realm` in the `Realm Roles` section

The `Full Scope Allowed` flag is especially important to disable, because if enabled,
the access token will contain *all* the accesses to all the realms,
leading to the token becoming too large after roughly 40 created realms
(receiving `431 Request Header Fields Too Large` responses in this case).

### Run

To start running tests:

    ./kcb.sh

By default, tests expect Keycloak running on http://localhost:8080/auth, and the default scenario is `keycloak.scenarion.authentication.ClientScret`.

To use a different server URL and scenario:

    ./kcb.sh --scenario=keycloak.scenario.authentication.AuthorizationCode --server-url=http://localhost:8080

### Options

You can set different options to change how tests should be executed. For a complete list of the available options, see
[Config](src/main/java/org/keycloak/benchmark/Config.java).

### Report

Check reports at the `result` directory.

### Test Scenarios

These are the available test scenarios:

* `keycloak.scenario.authentication.AuthorizationCode`: Authorization Code Grant Type
* `keycloak.scenario.authentication.LoginUserPassword`: Browser Login (only Authorization Endpoint. After username+password login, there is no exchange of OAuth2 "code" for the tokens) 
* `keycloak.scenario.authentication.ClientSecret`: Client Secret (Client Credentials Grant)
* `keycloak.scenario.admin.CreateDeleteClients`: Create and deleted clients (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.UserCrawl`: Crawls all users page by page (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateRealms`: Create realms (requires `--client-secret=<client secret for gatling client>` and `--realm-name=master`)

## Release

If you need to do changes in the "dataset" and then consume it for example from the Openshift pods, you may need the ability to push
your changes to the Keycloak and the release it. The info on how to release is in the [RELEASE.md](../RELEASE.md).