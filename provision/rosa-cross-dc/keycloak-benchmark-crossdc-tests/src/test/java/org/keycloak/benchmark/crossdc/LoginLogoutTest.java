package org.keycloak.benchmark.crossdc;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.keycloak.benchmark.crossdc.util.InfinispanUtils.SESSIONS;


public class LoginLogoutTest extends AbstractCrossDCTest {
    @Test
    public void loginLogoutTest() throws URISyntaxException, IOException, InterruptedException {
        //Login and exchange code in DC1
        String code = LOAD_BALANCER_KEYCLOAK.usernamePasswordLogin(REALM_NAME, USERNAME, MAIN_PASSWORD, CLIENTID);
        Map<String, Object> tokensMap = LOAD_BALANCER_KEYCLOAK.exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 200, code);

        //Making sure the code cannot be reused in any of the DCs
        DC_2.kc().exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 400, code, LOAD_BALANCER_KEYCLOAK.getRedirectUri(REALM_NAME));
        DC_1.kc().exchangeCode(REALM_NAME, CLIENTID, CLIENT_SECRET, 400, code, LOAD_BALANCER_KEYCLOAK.getRedirectUri(REALM_NAME));

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC1
        String sessionId = code.split("[.]")[1];
        assertTrue(DC_1.ispn().cache(SESSIONS).contains(sessionId),
                () -> "External session cache in DC1 should contain session id [" + sessionId + "] but contains " + DC_1.ispn().cache(SESSIONS).keys());

        //Verify session cache size in external ISPN DC1
        //Contains 2 sessions because admin client creates one and the test the other
        assertEquals(1, DC_1.ispn().cache(SESSIONS).size(),
                () -> "External session cache in DC1 should contain 2 sessions " + sessionId + " but contains " + DC_1.ispn().cache(SESSIONS).keys());

        //Verify session cache size in embedded ISPN DC1
        //Contains 2 sessions because admin client creates one and the test the other
        assertEquals(1, DC_1.kc().embeddedIspn().cache(SESSIONS).size());

        //Verify if the user session UUID in code, we fetched from Keycloak exists in session cache key of external ISPN in DC2
        assertTrue(DC_2.ispn().cache(SESSIONS).contains(sessionId),
                () -> "External session cache in DC2 should contains session id [" + sessionId + "] but contains " + DC_2.ispn().cache(SESSIONS).keys());
        //Verify session cache size in external ISPN DC2
        assertEquals(1, DC_2.ispn().cache(SESSIONS).size());
        //Verify session cache size in embedded ISPN DC2
        assertEquals(1, DC_2.kc().embeddedIspn().cache(SESSIONS).size());

        //Logout from DC1
        LOAD_BALANCER_KEYCLOAK.logout(REALM_NAME, (String) tokensMap.get("id_token"), CLIENTID);

        //Verify session cache size in external ISPN DC1 post logout
        assertEquals(0, DC_1.ispn().cache(SESSIONS).size());
        //Verify session cache size in embedded ISPN DC1 post logout
        assertEquals(0, DC_1.kc().embeddedIspn().cache(SESSIONS).size());

        //Verify session cache size in external ISPN  DC2 post logout
        assertEquals(0, DC_2.ispn().cache(SESSIONS).size());
        //Verify session cache size in embedded ISPN DC2
        assertEquals(0, DC_2.kc().embeddedIspn().cache(SESSIONS).size());
    }
}
