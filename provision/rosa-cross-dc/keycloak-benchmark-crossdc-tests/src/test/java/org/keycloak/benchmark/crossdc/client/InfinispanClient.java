package org.keycloak.benchmark.crossdc.client;

import org.infinispan.client.hotrod.RemoteCache;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Set;

public interface InfinispanClient<T extends InfinispanClient.Cache> {
    interface Cache {
        long size();

        void clear();

        boolean contains(String key) throws URISyntaxException, IOException, InterruptedException;

        boolean remove(String key);

        Set<String> keys();

        String name();
    }

    interface ExternalCache extends Cache {
        void takeOffline(String backupSiteName);
        void bringOnline(String backupSiteName);
        boolean isBackupOnline(String backupSiteName) throws IOException;
        RemoteCache getRemoteCache();
    }

    T cache(String name);

    default void close() {

    }
}
