package org.keycloak.benchmark.dataset;

import static org.keycloak.benchmark.dataset.config.DatasetOperation.CREATE_AUTHZ_CLIENT;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import org.jboss.resteasy.annotations.cache.NoCache;
import org.keycloak.authorization.AuthorizationProvider;
import org.keycloak.authorization.model.Policy;
import org.keycloak.authorization.model.Resource;
import org.keycloak.authorization.model.ResourceServer;
import org.keycloak.authorization.model.Scope;
import org.keycloak.authorization.store.PolicyStore;
import org.keycloak.authorization.store.ResourceStore;
import org.keycloak.authorization.store.ScopeStore;
import org.keycloak.authorization.store.StoreFactory;
import org.keycloak.benchmark.dataset.config.ConfigUtil;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.KeycloakSessionTaskWithResult;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.representations.idm.authorization.AggregatePolicyRepresentation;
import org.keycloak.representations.idm.authorization.DecisionStrategy;
import org.keycloak.representations.idm.authorization.ScopePermissionRepresentation;
import org.keycloak.representations.idm.authorization.UserPolicyRepresentation;

public class AuthorizationProvisioner extends DatasetResourceProvider{

    public AuthorizationProvisioner(KeycloakSession session) {
        super(session);
    }

    @Path("create-resources")
    @GET
    @NoCache
    @Produces(MediaType.APPLICATION_JSON)
    public Response provision() {
        boolean running = false;
        boolean taskAdded = false;

        try {
            DatasetConfig config = ConfigUtil.createConfigFromQueryParams(httpRequest, CREATE_AUTHZ_CLIENT);
            Task task = addTaskIfNotInProgress(config);

            if (task == null) {
                return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
            }

            taskAdded = true;

            AuthorizationProvider provider = getAuthorizationProvider(baseSession);
            ResourceStore resourceStore = provider.getStoreFactory().getResourceStore();
            ResourceServer resourceServer = getOrCreateResourceServer(config, baseSession);
            int startIndex = getLastResourceIndex(resourceServer, resourceStore);

            config.setStart(startIndex);

            RealmContext context = new RealmContext(config);
            RealmModel realm = getRealm(config, baseSession);

            context.setRealm(realm);

            running = submitTask(context, task);

            return Response.ok(TaskResponse.taskStarted(task, getStatusUrl())).build();
        } catch (DatasetException de) {
            return handleDatasetException(de);
        } finally {
            if (taskAdded && !running) {
                new TaskManager(baseSession).removeExistingTask(false);
            }
        }
    }

    private void provision(Task task, RealmContext context, KeycloakSession session, int resourceStartIndex, int resourceEndIndex) {
        AuthorizationProvider provider = session.getProvider(AuthorizationProvider.class);
        StoreFactory storeFactory = provider.getStoreFactory();
        ScopeStore scopeStore = storeFactory.getScopeStore();
        DatasetConfig config = context.getConfig();
        ResourceServer resourceServer = getOrCreateResourceServer(config, session);
        int scopeCount = config.getScopesPerResource();
        String scopePrefix = config.getScopePrefix();

        ResourceStore resourceStore = storeFactory.getResourceStore();
        PolicyStore policyStore = storeFactory.getPolicyStore();

        for (int currentResourceIndex = resourceStartIndex; currentResourceIndex < resourceEndIndex; currentResourceIndex++) {
            Set<Scope> scopes = createScopes(scopeStore, resourceServer, scopeCount, scopePrefix);
            Resource resource = createResource(config, resourceServer, resourceStore, currentResourceIndex, scopes);
            List<Policy> policies = createUserPolicies(config, resourceServer, policyStore, currentResourceIndex);
            createScopePermission(scopeStore, resourceServer, scopeCount, scopePrefix, policyStore, currentResourceIndex, resource, policies);
            context.incResourceCount();
        }

        task.debug(logger, "Created %d resources in client %s", context.getResourceCount(), config.getClientId());
        task.debug(logger, "Created resources in client %s from %d to %d", config.getClientId(), resourceStartIndex, resourceEndIndex);

        if (((resourceEndIndex - config.getStart()) / config.getClientsPerTransaction()) % 20 == 0) {
            task.info(logger, "Created %d resources in client %s", context.getResourceCount(), resourceServer.getClientId());
        }
    }

    private boolean submitTask(RealmContext context, Task task) {
        KeycloakModelUtils.runJobInTransaction(baseSession.getKeycloakSessionFactory(), new KeycloakSessionTask() {
            @Override
            public void run(KeycloakSession session) {
                DatasetConfig config = context.getConfig();
                ResourceServer resourceServer = getOrCreateResourceServer(config, session);
                AuthorizationProvider provider = session.getProvider(AuthorizationProvider.class);
                StoreFactory storeFactory = provider.getStoreFactory();
                ScopeStore scopeStore = storeFactory.getScopeStore();
                int startScopeIndex = getLastScopeIndex(resourceServer, scopeStore);
                int scopeCount = config.getScopesPerResource();
                String scopePrefix = config.getScopePrefix();

                for (int i = startScopeIndex; i < scopeCount; i++) {
                    scopeStore.create(resourceServer, scopePrefix + i);
                }
            }
        });
        new Thread(new Runnable() {
            @Override
            public void run() {
                KeycloakSessionFactory sessionFactory = baseSession.getKeycloakSessionFactory();
                DatasetConfig config = context.getConfig();
                RealmModel realm = context.getRealm();
                ExecutorHelper executor = new ExecutorHelper(config.getThreadsCount(), sessionFactory, config);

                try {
                    logger.infof("Will start creating resources in client '%s' from '%s' to '%s'", config.getClientId(), config.getResourcePrefix() + config.getStart(), config.getResourcePrefix() + (
                            config.getStart() + config.getCount() - 1));

                    for (int i = config.getStart(); i < (config.getStart() + config.getCount()); i += config.getEntriesPerTransaction()) {
                        int resourceStartIndex = i;
                        int endIndex = Math.min(resourceStartIndex + config.getEntriesPerTransaction(), config.getStart() + config.getCount());

                        // Run this concurrently with multiple threads
                        executor.addTaskRunningInTransaction(session -> {
                            session.getContext().setRealm(realm);

                            // Eagerly register invalidation to make sure we don't cache the realm in this transaction. Caching will result in bunch of
                            // unneeded SQL queries (triggered from constructor of org.keycloak.models.cache.infinispan.entities.CachedRealm) and we need to invalidate realm anyway in this transaction
                            RealmProvider realmProvider = session.realms();

                            if (realmProvider instanceof CacheRealmProvider) {
                                ((CacheRealmProvider) realmProvider).registerRealmInvalidation(realm.getId(), realm.getName());
                            }

                            provision(task, context, session, resourceStartIndex, endIndex);

                        });

                    }

                    executor.waitForAllToFinish();
                    success();
                    task.info(logger, "Created all %d resources in client %s and realm %s", context.getResourceCount(), config.getClientId(), realm.getName());
                } catch (Exception ex) {
                    logException(ex);
                } finally {
                    cleanup(executor);
                }
            }
        }).start();

        return true;
    }

    private void createScopePermission(ScopeStore scopeStore, ResourceServer resourceServer, int scopeCount, String scopePrefix,
            PolicyStore policyStore, int currentResourceIndex, Resource resource, List<Policy> policies) {
        for (int k = 0; k < scopeCount; k++) {
            ScopePermissionRepresentation permission = new ScopePermissionRepresentation();

            permission.setName(scopePrefix + k + "-permission-" + currentResourceIndex);
            permission.addResource(resource.getId());
            permission.addScope(scopeStore.findByName(resourceServer, scopePrefix + k).getId());
            permission.addPolicy(policies.stream().map(Policy::getId).toArray(String[]::new));

            policyStore.create(resourceServer, permission);
        }
    }

    private List<Policy> createUserPolicies(DatasetConfig config, ResourceServer resourceServer, PolicyStore policyStore, int currentResourceIndex) {
        List<Policy> policies = new ArrayList<>();

        if (config.getUsersPerUserPolicy() == 1) {
            policies.add(createUserPolicy(config, resourceServer, policyStore, currentResourceIndex));
        } else {
            AggregatePolicyRepresentation policyRep = new AggregatePolicyRepresentation();

            policyRep.setName("aggregated-" + currentResourceIndex);
            policyRep.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);

            Policy policy = policyStore.create(resourceServer, policyRep);

            for (int i = 0; i < config.getUsersPerUserPolicy(); i++) {
                policy.addAssociatedPolicy(createUserPolicy(config, resourceServer, policyStore, currentResourceIndex));
            }

            policies.add(policy);
        }

        return policies;
    }

    private Resource createResource(DatasetConfig config, ResourceServer resourceServer,
            ResourceStore resourceStore, int currentResourceIndex, Set<Scope> scopes) {
        Resource resource = resourceStore.create(resourceServer, config.getResourcePrefix() + currentResourceIndex, resourceServer.getId());

        resource.updateScopes(scopes);

        return resource;
    }

    private Set<Scope> createScopes(ScopeStore scopeStore, ResourceServer resourceServer, int scopeCount,
            String scopePrefix) {
        Set<Scope> scopes = new HashSet<>();

        for (int i = 0; i < scopeCount; i++) {
            scopes.add(scopeStore.findByName(resourceServer, scopePrefix + i));
        }
        return scopes;
    }

    private Policy createUserPolicy(DatasetConfig config, ResourceServer resourceServer, PolicyStore policyStore,
            int currentResourceIndex) {
        UserPolicyRepresentation policy = new UserPolicyRepresentation();
        String userName = config.getUserPrefix() + new Random().ints(0, 1000).findFirst().getAsInt();

        policy.setName(userName + "-policy-" + currentResourceIndex);

        Policy existingPolicy = policyStore.findByName(resourceServer, policy.getName());

        if (existingPolicy == null) {
            policy.addUser(userName);
            policy.setDecisionStrategy(DecisionStrategy.AFFIRMATIVE);
            return policyStore.create(resourceServer, policy);
        }

        return existingPolicy;
    }

    private int getLastResourceIndex(ResourceServer resourceServer, ResourceStore resourceStore) {
        int startIndex = ConfigUtil.findFreeEntityIndex(index -> {
            String resourceName = "resource-" + index;
            return resourceStore.findByName(resourceServer, resourceName) != null;
        });
        return startIndex;
    }

    private int getLastScopeIndex(ResourceServer resourceServer, ScopeStore scopeStore) {
        return ConfigUtil.findFreeEntityIndex(index -> scopeStore.findByName(resourceServer, "scope-" + index) != null);
    }

    private AuthorizationProvider getAuthorizationProvider(KeycloakSession session) {
        return session.getProvider(AuthorizationProvider.class);
    }

    private ResourceServer getOrCreateResourceServer(DatasetConfig config, KeycloakSession session) {
        AuthorizationProvider provider = getAuthorizationProvider(session);
        RealmModel realm = getRealm(config, session);
        ClientModel client = realm.getClientByClientId(config.getClientId());
        ResourceServer resourceServer = provider.getStoreFactory().getResourceServerStore().findByClient(client);

        if (resourceServer == null) {
            return KeycloakModelUtils.runJobInTransactionWithResult(session.getKeycloakSessionFactory(),
                    new KeycloakSessionTaskWithResult<ResourceServer>() {
                        @Override
                        public ResourceServer run(KeycloakSession session) {
                            AuthorizationProvider provider = getAuthorizationProvider(session);
                            return provider.getStoreFactory().getResourceServerStore().create(client);
                        }
                    });
        }

        return resourceServer;
    }

    private Task addTaskIfNotInProgress(DatasetConfig config) {
        RealmModel realm = getRealm(config, baseSession);
        ClientModel client = realm.getClientByClientId(config.getClientId());

        if (client == null) {
            throw new DatasetException("Client '" + config.getClientId() + "' not found in realm '" + realm.getName() + "'");
        }

        Task task = Task.start("Creation of authorization settings in realm " + config.getRealmName() + " for client " + config.getClientId());
        TaskManager taskManager = new TaskManager(baseSession);
        Task existingTask = taskManager.addTaskIfNotInProgress(task, config.getTaskTimeout());

        if (existingTask != null) {
            return null;
        }

        logger.infof("Creating task to provision authorization settings for client '%s': %s", config.getClientId(), config);

        return task;
    }

    private RealmModel getRealm(DatasetConfig config, KeycloakSession session) {
        // Avoid cache (Realm will be invalidated from the cache anyway)
        RealmModel realm = session.getProvider(RealmProvider.class).getRealmByName(config.getRealmName());

        if (realm == null) {
            throw new DatasetException("Realm '" + config.getRealmName() + "' not found");
        }

        return realm;
    }
}
