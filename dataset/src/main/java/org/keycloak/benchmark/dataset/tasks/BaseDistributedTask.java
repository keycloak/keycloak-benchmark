package org.keycloak.benchmark.dataset.tasks;

import org.infinispan.commons.util.Util;
import org.infinispan.executors.LimitedExecutor;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.concurrent.BlockingManager;
import org.infinispan.util.function.SerializableFunction;
import org.infinispan.util.function.TriConsumer;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSessionFactory;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class BaseDistributedTask implements SerializableFunction<EmbeddedCacheManager, String>, TriConsumer<Address, String, Throwable> {

    protected static final Logger logger = Logger.getLogger(MethodHandles.lookup().lookupClass());

    private final int start;
    private final int count;
    private final int concurrency;
    private final int transactionTimeout;
    private final String realmName;
    private final int entitiesPerTransaction;

    protected BaseDistributedTask(String realmName, int start, int count, int concurrency, int entitiesPerTransaction, int transactionTimeout) {
        this.realmName = realmName;
        this.start = start;
        this.count = count;
        this.concurrency = concurrency;
        this.transactionTimeout = transactionTimeout;
        this.entitiesPerTransaction = entitiesPerTransaction;
    }

    public String getRealmName() {
        return realmName;
    }

    public int getEntitiesPerTransaction() {
        return entitiesPerTransaction;
    }

    public int getTransactionTimeout() {
        return transactionTimeout;
    }

    @Override
    public String toString() {
        return ", start=" + start +
                ", count=" + count +
                ", concurrency=" + concurrency +
                ", transactionTimeout=" + transactionTimeout +
                ", realmName='" + realmName + '\'' +
                ", entitiesPerTransaction=" + entitiesPerTransaction +
                '}';
    }

    @SuppressWarnings("removal")
    @Override
    public final String apply(EmbeddedCacheManager cacheManager) {
        var sessionFactory = cacheManager.getCache("work").getCacheManager().getGlobalComponentRegistry().getComponent(KeycloakSessionFactory.class, "dataset");
        if (sessionFactory == null) {
            throw new IllegalStateException();
        }
        var transport = cacheManager.getTransport();
        var localAddress = transport.getAddress();
        var clusterSize = transport.getMembers().size();
        var localIndex = transport.getMembers().indexOf(localAddress);
        var isLastNode = localIndex == (clusterSize - 1);

        var executor = cacheManager.getGlobalComponentRegistry()
                .getComponent(BlockingManager.class)
                .asExecutor("dataset");
        // BlockingExecutor limitedBlockingExecutor() method is "optimized" to not spawn thread if the invoked is a thread from the "blocking thread pool"
        // It translates into a sequential run; it is not what we want.
        // The name is for tracing logging purposes.
        var limitedExecutor = new LimitedExecutor("dataset-limited", executor, concurrency);

        logger.infof("Node '%s' (index=%s, cluster-size=%s) received a new task: %s", localAddress, localIndex, clusterSize, this);


        var clientsPerNode = count / clusterSize;
        var remainder = count % clusterSize;

        var startIndex = start + (clientsPerNode * localIndex);
        var endIndex = startIndex + clientsPerNode + (isLastNode ? remainder : 0);

        var startMs = System.currentTimeMillis();
        var counter = new AtomicInteger();

        runTask(localAddress, sessionFactory, startIndex, endIndex, limitedExecutor, counter);

        var duration = Util.prettyPrintTime(System.currentTimeMillis() - startMs);

        logger.infof("Node '%s' (index=%s, cluster-size=%s) finished task in %s", localAddress, localIndex, clusterSize, duration);

        return String.format("Created %s entities. Took %s", counter.get(), duration);
    }

    @Override
    public final void accept(Address src, String rsp, Throwable ex) {
        if (ex != null) {
            logger.errorf(ex, "Received error from node %s", src);
        } else {
            logger.infof("Received response from node %s: %s", src, rsp);
        }
    }

    abstract void runTask(Address localAddress, KeycloakSessionFactory sessionFactory, int startIndex, int endIndex, Executor executor, AtomicInteger counter);


}
