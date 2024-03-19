package org.keycloak.benchmark.dataset.tasks;

import org.infinispan.remoting.transport.Address;
import org.infinispan.util.concurrent.AggregateCompletionStage;
import org.infinispan.util.concurrent.CompletionStages;
import org.keycloak.benchmark.dataset.config.DatasetConfig;
import org.keycloak.benchmark.dataset.config.DatasetException;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakSessionTask;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserCredentialModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.KeycloakModelUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.keycloak.benchmark.dataset.DatasetResourceProvider.GROUP_NAME_SEPARATOR;

public class CreateUsersDistributedTask extends BaseDistributedTask {

    private final String realmRolePrefix;
    private final String groupPrefix;
    private final String clientPrefix;
    private final String clientRolePrefix;

    final String userPrefix;
    final int realmRolesPerUser;
    final int clientRolesPerUser;
    final int groupsPerUser;

    public CreateUsersDistributedTask(String realmName, DatasetConfig config) {
        this(realmName, config.getStart(), config.getCount(), config);
    }

    public CreateUsersDistributedTask(String realmName, int start, int count, DatasetConfig config) {
        super(realmName, start, count, config.getThreadsCount(), config.getUsersPerTransaction(), config.getTransactionTimeoutInSeconds());
        realmRolePrefix = config.getRealmRolePrefix();
        groupPrefix = config.getGroupPrefix();
        clientPrefix = config.getClientPrefix();
        clientRolePrefix = config.getClientRolePrefix();
        userPrefix = config.getUserPrefix();
        realmRolesPerUser = config.getRealmRolesPerUser();
        clientRolesPerUser = config.getClientRolesPerUser();
        groupsPerUser = config.getGroupsPerUser();
    }

    @Override
    void runTask(Address localAddress, KeycloakSessionFactory sessionFactory, int startIndex, int endIndex, Executor executor, AtomicInteger counter) {
        logger.infof("Node '%s' will create users from %s (inclusive) to %s (exclusive)", localAddress, startIndex, endIndex);
        try {
            AggregateCompletionStage<Void> stage = CompletionStages.aggregateCompletionStage();
            var context = KeycloakModelUtils.runJobInTransactionWithResult(sessionFactory, this::createUserContext);
            // Create clients now
            for (int i = startIndex; i < endIndex; i += getEntitiesPerTransaction()) {
                var tx = new CreateUsersTx(sessionFactory, context, counter, i, Math.min(i + getEntitiesPerTransaction(), endIndex), this);
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

    private UserContext createUserContext(KeycloakSession session) {
        RealmModel realm = session.getProvider(RealmProvider.class).getRealmByName(getRealmName());

        List<RoleModel> realmRoles = realm.getRolesStream()
                .filter(roleModel -> roleModel.getName().startsWith(realmRolePrefix))
                .sorted((role1, role2) -> {
                    String name1 = role1.getName().substring(realmRolePrefix.length());
                    String name2 = role2.getName().substring(realmRolePrefix.length());
                    return Integer.parseInt(name1) - Integer.parseInt(name2);
                })
                .collect(Collectors.toList());

        logger.debugf("CACHE: After obtain realm roles in realm %s", realm.getName());

        List<GroupModel> sortedGroups = realm.getGroupsStream()
                .filter(groupModel -> groupModel.getName().startsWith(groupPrefix))
                .sorted((group1, group2) -> {
                    String name1 = group1.getName().substring(groupPrefix.length());
                    String name2 = group2.getName().substring(groupPrefix.length());
                    String[] name1Exploded = name1.split(Pattern.quote(GROUP_NAME_SEPARATOR));
                    String[] name2Exploded = name2.split(Pattern.quote(GROUP_NAME_SEPARATOR));
                    for (int i = 0; i < Math.min(name1Exploded.length, name2Exploded.length); i++) {
                        if (name1Exploded[i].compareTo(name2Exploded[i]) != 0) {
                            return name1Exploded[i].compareTo(name2Exploded[i]);
                        }
                    }
                    return name1.compareTo(name2);
                })
                .collect(Collectors.toList());

        List<RoleModel> clientRoles = realm.getClientsStream(0, 20)
                .filter(clientModel -> clientModel.getClientId().startsWith(clientPrefix))
                .sorted((client1, client2) -> {
                    String name1 = client1.getClientId().substring(clientPrefix.length());
                    String name2 = client2.getClientId().substring(clientPrefix.length());
                    return Integer.parseInt(name1) - Integer.parseInt(name2);
                })
                .flatMap(RoleContainerModel::getRolesStream)
                .filter(roleModel -> roleModel.getName().startsWith(clientPrefix))
                .sorted((role1, role2) -> {
                    int index1 = role1.getName().indexOf(clientRolePrefix) + clientRolePrefix.length();
                    int index2 = role2.getName().indexOf(clientRolePrefix) + clientRolePrefix.length();
                    String name1 = role1.getName().substring(index1);
                    String name2 = role2.getName().substring(index2);
                    return Integer.parseInt(name1) - Integer.parseInt(name2);
                })
                .collect(Collectors.toList());

        logger.debugf("CACHE: After client roles loaded in the realm %s", realm.getName());
        return new UserContext(realmRoles, clientRoles, sortedGroups);
    }

    @Override
    public String toString() {
        return "CreateUsersDistributedTask{" +
                "realmRolePrefix='" + realmRolePrefix + '\'' +
                ", groupPrefix='" + groupPrefix + '\'' +
                ", clientPrefix='" + clientPrefix + '\'' +
                ", clientRolePrefix='" + clientRolePrefix + '\'' +
                ", userPrefix='" + userPrefix + '\'' +
                ", realmRolesPerUser=" + realmRolesPerUser +
                ", clientRolesPerUser=" + clientRolesPerUser +
                ", groupsPerUser=" + groupsPerUser +
                super.toString();
    }

    record UserContext(List<RoleModel> realmRoles, List<RoleModel> clientRoles, List<GroupModel> groups) {

        RoleModel getRealmRole(int index) {
            var roleIndex = index % realmRoles.size();
            return realmRoles.get(roleIndex);
        }

        RoleModel getClientRole(int index) {
            var roleIndex = index % clientRoles.size();
            return clientRoles.get(roleIndex);
        }

        GroupModel getGroup(int index) {
            var groupIndex = index % groups.size();
            return groups.get(groupIndex);
        }

    }

    private record CreateUsersTx(KeycloakSessionFactory sessionFactory, UserContext context, AtomicInteger counter,
                                 int startIndex, int endIndex,
                                 CreateUsersDistributedTask task) implements KeycloakSessionTask, Runnable {

        @Override
        public void run() {
            KeycloakModelUtils.runJobInTransactionWithTimeout(sessionFactory, this, task.getTransactionTimeout());
        }

        @Override
        public void run(KeycloakSession session) {
            RealmModel realm = session.realms().getRealm(task.getRealmName());

            for (int i = startIndex; i < endIndex; i++) {
                String username = task.userPrefix + i;
                UserModel user = session.users().addUser(realm, username);
                user.setEnabled(true);
                user.setFirstName(username + "-first");
                user.setLastName(username + "-last");
                user.setEmail(username + String.format("@%s.com", realm.getName()));

                String password = String.format("%s-password", username);
                user.credentialManager().updateCredential(UserCredentialModel.password(password, false));

                // Detect which roles we assign to the user
                int roleIndexStartForCurrentUser = (i * task.realmRolesPerUser);
                for (int j = roleIndexStartForCurrentUser; j < roleIndexStartForCurrentUser + task.realmRolesPerUser; j++) {
                    var role = context.getRealmRole(j);
                    user.grantRole(role);

                    logger.tracef("Assigned role %s to the user %s", role.getName(), user.getUsername());
                }

                int clientRoleIndexStartForCurrentUser = (i * task.clientRolesPerUser);
                for (int j = clientRoleIndexStartForCurrentUser; j < clientRoleIndexStartForCurrentUser + task.clientRolesPerUser; j++) {
                    var role = context.getClientRole(j);
                    user.grantRole(role);

                    logger.tracef("Assigned role %s to the user %s", role.getName(), user.getUsername());
                }

                // Detect which groups we assign to the user
                int groupIndexStartForCurrentUser = (i * task.groupsPerUser);
                for (int j = groupIndexStartForCurrentUser; j < groupIndexStartForCurrentUser + task.groupsPerUser; j++) {
                    var group = context.getGroup(j);
                    user.joinGroup(group);

                    logger.tracef("Assigned group %s to the user %s", group.getName(), user.getUsername());
                }
                counter.incrementAndGet();
            }

            if (((endIndex - startIndex) / task.getEntitiesPerTransaction()) % 20 == 0) {
                logger.infof("Created %d users in realm %s", counter.get(), task.getRealmName());
            }
        }
    }
}
