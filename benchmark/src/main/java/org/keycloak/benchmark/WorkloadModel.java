package org.keycloak.benchmark;

/**
 *
 * @author tkyjovsk
 */
public enum WorkloadModel {

    OPEN, // load generation defined by user arrival rate (usersPerSec)
    CLOSED // loag generation defined by number of concurrent users (concurrentUsers)

}
