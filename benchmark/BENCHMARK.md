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

Some scenarios require a service account with the clientId `gatling`:

* select the realm that is used for testing
* create a client  with the name `gatling`
   * set access type to `confidential`
   * check `Service Account Enabled`
   * enter a valid redirect uri (e.g. `http://localhost`)  
   * click save
* Change to the tab `Sevice Account Roles`
   * select for `realm-management` in the `Client Roles` listbox
   * assign the roles, based on the below role-mapping table for the respective load simulation scenario
* the client secret to be passed to the tests can be copied from the `Credentials` tab

| Scenario Name           |       Assigned Roles       |
|-------------------------|:--------------------------:|
| CreateClient            | manage-clients, view-users |
| CreateDeleteClient      | manage-clients, view-users |
| CrawlUsers              | manage-clients, view-users |
| CreateRole              |        manage-realm        |
| CreateDeleteRole        |        manage-realm        |
| CreateClientScope       | manage-clients, view-users |
| CreateDeleteClientScope | manage-clients, view-users |
| CreateGroup             |        manage-users        |
| CreateDeleteGroup       |        manage-users        |
| CreateUsers             |        manage-users        |
| CreateDeleteUsers       |        manage-users        |

Instead of following the above manual steps, you can use this [manage_gatling_client](manage_gatling_client.sh) script to do the setup for you.

Login to the Keycloak server using the kcadm cli script, which comes with any Keycloak distribution

```shell
$KEYCLOAK_HOME/bin/kcadm.sh config credentials --server http://localhost:8081/auth --realm master --user admin --password admin
```

and then run this, for creating the needed realm and client

```shell
./manage_gatling_client.sh -c gatling
```

or run this, to recreate the realm and client for any reason

```shell
./manage_gatling_client.sh -d
```



#### Scenarios `keycloak.scenario.admin.CreateRealms` and `keycloak.scenario.admin.CreateDeleteRealms`

These scenarios are using the root admin account to perform realm operations with the built-in `admin-cli` client.

This information is specified to the scenarios with options `--admin-username` and `--admin-password`.

Usage of a service account token is irrelevant with these scenarios, because:

* real-world realm operations are performed using root admin credentials
* deleting a just-created realm requires realm-specific permissions which are set onto a realm-specific
  client, which would require to logout then login again using the realm-specific client to perform the realm deletion operation
* as the token includes all realm permissions, it would grow very fast and would quickly exceed
  the maximum length for header (leading to `431 Request Header Fields Too Large` responses).

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
* `keycloak.scenario.admin.CreateDeleteClient`: Create and delete clients (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateClient`: Create clients (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateDeleteUsers`: Create and delete users (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateUsers`: Create users.. (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateDeleteRole`: Create and delete roles (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateRole`: Create roles (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateDeleteGroup`: Create and delete groups (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateGroup`: Create groups (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateDeleteClientScope`: Create and delete client scopes (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateClientScope`: Create client scope (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.UserCrawl`: Crawls all users page by page (requires `--client-secret=<client secret for gatling client>`)
* `keycloak.scenario.admin.CreateRealm`: Create realms (requires `--admin-username=<admin login>` and `--admin-password=<admin password>`)
* `keycloak.scenario.admin.CreateDeleteRealm`: Create and immediately delete realms (requires `--admin-username=<admin login>` and `--admin-password=<admin password>`)

## Release

If you need to do changes in the "dataset" and then consume it for example from the Openshift pods, you may need the ability to push
your changes to the Keycloak and the release it. The info on how to release is in the [RELEASE.md](../RELEASE.md).