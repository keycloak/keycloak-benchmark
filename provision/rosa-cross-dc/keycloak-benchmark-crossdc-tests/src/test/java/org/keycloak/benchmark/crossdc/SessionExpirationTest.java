package org.keycloak.benchmark.crossdc;

import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.CLIENT_SESSION_CACHE_NAME;
import static org.keycloak.connections.infinispan.InfinispanConnectionProvider.USER_SESSION_CACHE_NAME;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.RealmRepresentation;

public class SessionExpirationTest extends AbstractCrossDCTest {

    @Test
    public void sessionExpirationTest() throws IOException, URISyntaxException, InterruptedException {
        // set user/client session lifespan to 5s
        RealmRepresentation realm = LOAD_BALANCER_KEYCLOAK.adminClient().realm(REALM_NAME).toRepresentation();
        realm.setSsoSessionMaxLifespan(5);
        realm.setClientSessionMaxLifespan(5);
        LOAD_BALANCER_KEYCLOAK.adminClient().realm(REALM_NAME).update(realm);

        // create a user and client session
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin(REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        // check the sessions are replicated in remote caches
        assertCacheSize(USER_SESSION_CACHE_NAME, 1);
        assertCacheSize(CLIENT_SESSION_CACHE_NAME, 1);

        // let them expire
        Thread.sleep(6000);

        // check the remote caches are empty
        assertCacheSize(USER_SESSION_CACHE_NAME, 0);
        assertCacheSize(CLIENT_SESSION_CACHE_NAME, 0);

        // token refresh should fail
        DC_2.kc().refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
        DC_1.kc().refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
    }
}
