package org.keycloak.benchmark.crossdc.client;

import java.util.Set;

public interface InfinispanClient {
    interface Cache {
        long size();
        void clear();
        boolean contains(String key);

        Set<String> keys();
    }


    public Cache cache(String name);
}
