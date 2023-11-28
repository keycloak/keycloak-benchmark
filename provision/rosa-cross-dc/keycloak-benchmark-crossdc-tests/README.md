### Motivation

We wanted to validate the Cross Data Center functionality of Keycloak in an actual cross data center setup for Keycloak application. As part of that we created a test suite that is currently hosted in the keycloak-benchmark and in future would move to a more appropriate place.

### CrossDC Test Framework

The current framework is made up of the below components

- **Testsuite root directory**: keycloak-benchmark/provision/rosa-cross-dc
- **Test Runner**: JUnit5.
- **Test Data**: Use [Keycloak Dataset provider](https://www.keycloak.org/keycloak-benchmark/dataset-guide/latest/).
- **Cache Metrics**: [ISPN Http REST client](https://infinispan.org/docs/stable/titles/rest/rest.html) to access Cache stats for external ISPN server. And for embedded infinispan cache we are relying on [Dataset provider](https://www.keycloak.org/keycloak-benchmark/dataset-guide/latest/).
- **Execution Target**: A CrossDC cluster with access to two Keycloak and Infinispan datacenter urls.

<br/> Note: We will use the existing ROSA OCP cluster based deployment during development to bring up the cross-dc cluster.

### How to run

From the Testsuite root directory run the below command to run the tests

```
mvn clean install -DcrossDCTests \
-Dinfinispan.dc1.url=<ISPN_DC1_URL> -Dkeycloak.dc1.url=<KEYCLOAK_DC1_URL> \
-Dinfinispan.dc2.url=<ISPN_DC2_URL> -Dkeycloak.dc2.url=<KEYCLOAK_DC2_URL>\
-Dinfinispan.password=<ISPN_PASSWORD>
```

Alternatively could use the `run-crossdc-tests.sh` (located in the Testsuite root) directory to execute the tests when using a ROSA style provisioning setup to fetch the `ISPN_PASSWORD` on the fly, or by setting it manually.

Example usage:
```
ISPN_DC1_URL=<ISPN_DC1_URL> ISPN_DC2_URL=<ISPN_DC2_URL> \
KEYCLOAK_DC1_URL=<KEYCLOAK_DC1_URL> KEYCLOAK_DC2_URL=<KEYCLOAK_DC2_URL> \
./run-crossdc-tests.sh
```
