package org.keycloak.benchmark.crossdc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.CLIENT_SESSIONS;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.SESSIONS;

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
        assertEquals(1, DC_1.ispn().cache(SESSIONS).size());
        assertEquals(1, DC_2.ispn().cache(SESSIONS).size());
        assertEquals(1, DC_1.ispn().cache(CLIENT_SESSIONS).size());
        assertEquals(1, DC_2.ispn().cache(CLIENT_SESSIONS).size());

        // check the sessions are replicated in embedded caches
        assertEquals(1, DC_1.kc().embeddedIspn().cache(SESSIONS).size());
        assertEquals(1, DC_2.kc().embeddedIspn().cache(SESSIONS).size());
        assertEquals(1, DC_1.kc().embeddedIspn().cache(CLIENT_SESSIONS).size());
        assertEquals(1, DC_2.kc().embeddedIspn().cache(CLIENT_SESSIONS).size());

        // let them expire
        Thread.sleep(6000);

        // check the remote caches are empty
        assertEquals(0, DC_1.ispn().cache(SESSIONS).size());
        assertEquals(0, DC_2.ispn().cache(SESSIONS).size());
        assertEquals(0, DC_1.ispn().cache(CLIENT_SESSIONS).size());
        assertEquals(0, DC_2.ispn().cache(CLIENT_SESSIONS).size());

        // check the embedded caches are empty
        assertEquals(0, DC_1.kc().embeddedIspn().cache(SESSIONS).size());
        assertEquals(0, DC_2.kc().embeddedIspn().cache(SESSIONS).size());
        assertEquals(0, DC_1.kc().embeddedIspn().cache(CLIENT_SESSIONS).size());
        assertEquals(0, DC_2.kc().embeddedIspn().cache(CLIENT_SESSIONS).size());

        // token refresh should fail
        DC_2.kc().refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
        DC_1.kc().refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
    }
}
