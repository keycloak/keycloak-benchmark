package org.keycloak.benchmark.crossdc.util;

import java.util.Base64;
import java.util.Map;

import org.keycloak.benchmark.crossdc.client.InfinispanClient;

public class InfinispanUtils {

    public static String getBasicAuthenticationHeader(String username, String password) {
        String valueToEncode = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(valueToEncode.getBytes());
    }

    public static <T> T getNestedValue(Map map, String... keys) {
        Object value = map;
        for (String key : keys) {
            value = ((Map) value).get(key);
            if (value == null) {
                return null;
            }
        }
        return (T) value;
    }

    public static AutoCloseable withBackupDisabled(InfinispanClient.ExternalCache ispnCache, String backupSiteName) {
        ispnCache.takeOffline(backupSiteName);

        return () -> ispnCache.bringOnline(backupSiteName);
    }
}
