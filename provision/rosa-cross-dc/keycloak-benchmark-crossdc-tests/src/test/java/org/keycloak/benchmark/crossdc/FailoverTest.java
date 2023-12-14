package org.keycloak.benchmark.crossdc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class FailoverTest extends AbstractCrossDCTest {

    @Test
    public void logoutUserWithFailoverTest() throws IOException, URISyntaxException, InterruptedException {
        // Login and exchange code in DC1
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin( REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        DC_1.kc().markLBCheckDown();
        waitForFailover(LOAD_BALANCER_KEYCLOAK.getKeycloakServerUrl(), DC_1.getKeycloakServerURL(), DC_2.getKeycloakServerURL());

        // Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC2
        Set<String> sessions = DC_2.ispn().cache("sessions").keys();
        assertTrue(sessions.contains(code.toString().split("[.]")[1]));

        tokensMap = DC_2.kc().refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 200);

        DC_2.kc().logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        DC_2.kc().refreshToken(REALM_NAME, (String) tokensMap.get("refresh_token"), CLIENTID, CLIENT_SECRET, 400);
    }
}
