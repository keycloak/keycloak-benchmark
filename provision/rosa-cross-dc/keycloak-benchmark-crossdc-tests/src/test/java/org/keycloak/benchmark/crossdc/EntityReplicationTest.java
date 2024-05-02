package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.keycloak.benchmark.crossdc.util.KeycloakUtils.getCreatedId;

import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

public class EntityReplicationTest extends AbstractCrossDCTest {

    @Test
    public void keycloakEntityReplicationOverCacheTest() {
        UsersResource dc1Users = DC_1.kc().adminClient().realm(REALM_NAME).users();
        UsersResource dc2Users = DC_2.kc().adminClient().realm(REALM_NAME).users();

        // Create user in DC1
        UserRepresentation user = new UserRepresentation();
        user.setEnabled(Boolean.TRUE);
        user.setUsername("entity-replication-test-user");
        String createdUserId = getCreatedId(dc1Users.create(user));

        // Check if user is replicated to DC2
        UserRepresentation userDc2 = dc2Users.get(createdUserId).toRepresentation();
        assertNotNull(userDc2);
        assertEquals(user.getUsername(), userDc2.getUsername());
    }
}
