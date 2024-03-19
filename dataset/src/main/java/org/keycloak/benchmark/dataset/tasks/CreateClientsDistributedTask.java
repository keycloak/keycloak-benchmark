package org.keycloak.benchmark.dataset.tasks;

import org.infinispan.remoting.transport.Address;
import org.infinispan.util.concurrent.AggregateCompletionStage;
import org.infinispan.util.concurrent.CompletionStages;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.cache.CacheRealmProvider;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.protocol.oidc.OIDCAdvancedConfigWrapper;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.services.managers.ClientManager;
import org.keycloak.services.managers.RealmManager;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class CreateClientsDistributedTask extends BaseDistributedTask {

    final String clientPrefix;
    final String clientAccessType;
    final boolean serviceAccount;
    final int clientRolesPerClient;
    final String clientRolePrefix;

    public CreateClientsDistributedTask(DatasetConfig config) {
        this(config.getRealmName(), config.getStart(), config.getCount(), config);
    }

    public CreateClientsDistributedTask(String realmName, int start, int count, DatasetConfig config) {
        super(realmName, start, count, config.getThreadsCount(), config.getClientsPerTransaction(), config.getTransactionTimeoutInSeconds());
        clientPrefix = config.getClientPrefix();
        serviceAccount = Boolean.parseBoolean(config.getIsServiceAccountClient());
        clientAccessType = config.getClientAccessType();
        clientRolePrefix = config.getClientRolePrefix();
        clientRolesPerClient = config.getClientRolesPerClient();
    }

    @Override
    void runTask(Address localAddress, KeycloakSessionFactory sessionFactory, int startIndex, int endIndex, Executor executor, AtomicInteger counter) {
        logger.infof("Node '%s' will create clients from %s (inclusive) to %s (exclusive)", localAddress, startIndex, endIndex);
        try {
            AggregateCompletionStage<Void> stage = CompletionStages.aggregateCompletionStage();
            // Create clients now
            for (int i = startIndex; i < endIndex; i += getEntitiesPerTransaction()) {
                var tx = new CreateClientsTx(sessionFactory, counter, i, Math.min(i + getEntitiesPerTransaction(), endIndex), this);
                // Run this concurrently with multiple threads
                stage.dependsOn(CompletableFuture.runAsync(tx, executor));
            }
            stage.freeze().toCompletableFuture().get();
        } catch (ExecutionException e) {
            throw new DatasetException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record CreateClientsTx(KeycloakSessionFactory sessionFactory, AtomicInteger counter, int startIndex,
                                   int endIndex,
                                   CreateClientsDistributedTask task) implements KeycloakSessionTask, Runnable {

        @Override
        public void run() {
            KeycloakModelUtils.runJobInTransactionWithTimeout(sessionFactory, this, task.getTransactionTimeout());
        }

        @Override
        public void run(KeycloakSession session) {
            logger.tracef("clientsStartIndex: %d, clientsEndIndex: %d", startIndex, endIndex);
            RealmModel realm = session.getProvider(RealmProvider.class).getRealmByName(task.getRealmName());
            if (realm == null) {
                throw new DatasetException("Realm '" + task.getRealmName() + "' not found");
            }
            // Eagerly register invalidation to make sure we don't cache the realm in this transaction. Caching will result in bunch of
            // unneeded SQL queries (triggered from constructor of org.keycloak.models.cache.infinispan.entities.CachedRealm) and we need to invalidate realm anyway in this transaction
            RealmProvider realmProvider = session.realms();
            if (realmProvider instanceof CacheRealmProvider) {
                ((CacheRealmProvider) realmProvider).registerRealmInvalidation(realm.getId(), realm.getName());
            }

            // Refresh realm in current transaction
            realm = realmProvider.getRealm(realm.getId());

            for (int i = startIndex; i < endIndex; i++) {
                ClientRepresentation client = new ClientRepresentation();

                String clientId = task.clientPrefix + i;
                client.setClientId(clientId);
                client.setName(clientId);
                client.setEnabled(true);
                client.setDirectAccessGrantsEnabled(true);
                client.setSecret(clientId.concat("-secret"));
                client.setRedirectUris(List.of("*"));
                client.setProtocol(OIDCLoginProtocol.LOGIN_PROTOCOL);

                switch (task.clientAccessType) {
                    case "bearer-only":
                        client.setBearerOnly(true);
                        break;
                    case "public":
                        client.setPublicClient(true);
                        break;
                    case "confidential":
                    default:
                        client.setPublicClient(false);
                        break;
                }

                ClientModel model = ClientManager.createClient(session, realm, client);

                // Enable service account
                if (task.serviceAccount) {
                    new ClientManager(new RealmManager(session)).enableServiceAccount(model);
                }

                // Set "post.logout.redirect.uris"
                if (OIDCAdvancedConfigWrapper.fromClientModel(model).getPostLogoutRedirectUris() == null) {
                    OIDCAdvancedConfigWrapper.fromClientModel(model).setPostLogoutRedirectUris(Collections.singletonList("+"));
                }


                for (int k = 0; k < task.clientRolesPerClient; k++) {
                    String roleName = clientId + "-" + task.clientRolePrefix + k;
                    model.addRole(roleName);
                }
                counter.incrementAndGet();
            }
            if (((endIndex - startIndex) / task.getEntitiesPerTransaction()) % 20 == 0) {
                logger.infof("Created %d clients in realm %s", counter.get(), task.getRealmName());
            }
        }
    }

    @Override
    public String toString() {
        return "CreateClientsDistributedTask{" +
                "clientPrefix='" + clientPrefix + '\'' +
                ", clientAccessType='" + clientAccessType + '\'' +
                ", serviceAccount=" + serviceAccount +
                ", clientRolesPerClient=" + clientRolesPerClient +
                ", clientRolePrefix='" + clientRolePrefix + '\'' +
                super.toString();
    }
}
