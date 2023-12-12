package org.keycloak.benchmark.crossdc.util;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public class InfinispanUtils {

    public static String SESSIONS = "sessions";

    public static Set<String> DISTRIBUTED_CACHES = Set.of(
            SESSIONS,
            "actionTokens",
            "authenticationSessions",
            "offlineSessions",
            "clientSessions",
            "offlineClientSessions",
            "loginFailures",
            "work"
    );
    public static Set<String> LOCAL_CACHES = Set.of(
            "realms",
            "users",
            "authorization",
            "keys"
    );

    public static Set<String> ALL_CACHES = Stream.of(DISTRIBUTED_CACHES, LOCAL_CACHES)
            .flatMap(Set::stream)
            .collect(java.util.stream.Collectors.toSet());


    public static String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

    public static <T> T getNestedValue(Map map, String... keys) {
        Object value = map;
        for (String key : keys) {
            value = ((Map) value).get(key);
        }
        return (T) value;
    }
}
