## Provision

### docker-compose 

>Note: This provisioning model only supports Keycloak Legacy distribution, we are working on a version to support Quarkus based distribution.

#### Keycloak
A light weight Keycloak server setup with, PostgreSQL DB as its external store using docker-compose.

 - The image used for Keycloak server is the latest legacy container from [quay.io](https://quay.io/repository/keycloak/keycloak?tab=tags&tag=legacy)
 
##### Pre-requisite
The keycloak-legacy-postgres.yml file will work with the below docker and docker-compose versions

- Docker CE: 20.10.12
- Docker Compose: 1.29.2

##### Alternative podman

Podman is an alternative to docker that can run containers in a non-root way.
This setup is tested with:

- podman: 3.4.7
- Docker Compose: 1.29.2

To provide the DOCKER_HOST environment for Docker Compose to work, run the following script before running Docker Compose in this shell.: 

```shell
export DOCKER_HOST="unix:/run/user/$(id -u)/podman/podman.sock"
podman system service -t 3600 &
```

#### Bringing up the containers for the legacy distribution

_Note: The below steps assume you are inside the `provision/docker-compose` directory from the root of this git repository._

To run in a detached mode,

```shell
docker-compose -f keycloak/keycloak-legacy-postgres.yml up -d
```

- The Keycloak application will be available at http://localhost:8080/
- The PostgreSQL db will be available at localhost:5432

To view individual container logs,

```shell
docker-compose -f keycloak/keycloak-legacy-postgres.yml logs postgres
```

```shell
docker-compose -f keycloak/keycloak-legacy-postgres.yml logs keycloak
```

_Note: To use the latest available version of the Keycloak image, run the command below when there is a new release of Keycloak available._

```shell
docker-compose pull
```


##### Deploying the dataset module custom provider to this containerized Keycloak distribution

Assuming you have already built the [Dataset Module Readme](../README.md)

```shell
docker cp ../../dataset/target/keycloak-benchmark-dataset-*.jar keycloak:/opt/jboss/keycloak/standalone/deployments/
```

This will be a hot deploy, so restart of the container is not necessary.
We can confirm the deploy with a log similar to the below one, from the Keycloak server logs

```shell
08:28:15,630 INFO  [org.jboss.as.server] (DeploymentScanner-threads - 2) WFLYSRV0010: Deployed "keycloak-benchmark-dataset-0.7-SNAPSHOT.jar" (runtime-name : "keycloak-benchmark-dataset-0.7-SNAPSHOT.jar")
```

#### Bringing up the containers for the Quarkus distribution

_Note: The below steps assume you are inside the `provision/docker-compose` directory from the root of this git repository._

To run in a detached mode,

```shell
docker-compose -f keycloak/keycloak-quarkus-postgres.yml up -d
```

- The Keycloak application will be available at http://localhost:8080/
- The PostgreSQL db will be available at localhost:5432

To view individual container logs,

```shell
docker-compose -f keycloak/keycloak-quarkus-postgres.yml logs postgres
```

```shell
docker-compose -f keycloak/keycloak-quarkus-postgres.yml logs keycloak
```

_Note: To use the latest available version of the Keycloak image, run the command below when there is a new release of Keycloak available._

```shell
docker-compose pull
```

##### Deploying the dataset module custom provider to this containerized Keycloak distribution

Assuming you have already built the [Dataset Module Readme](../README.md)

```shell
mkdir -p keycloak/providers
cp ../../dataset/target/keycloak-benchmark-dataset-*.jar keycloak/providers
```

After that, restart the Keycloak instance. 

To test the installation, open the following URL http://localhost:8080/realms/master/dataset/status.
It should state

```json
{"status":"No task in progress. New task can be started"}
```
