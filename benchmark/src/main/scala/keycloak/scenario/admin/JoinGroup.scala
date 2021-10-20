package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}
import org.keycloak.benchmark.Config

class JoinGroup extends CommonSimulation {

  private val groupName = Config.joinGroup_groupName

  setUp("Join Group", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createUser()
    .findGroup(groupName)
    .joinGroup());
}
