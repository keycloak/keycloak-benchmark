package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateDeleteGroups extends CommonSimulation {

  setUp("Create, List and Delete Groups", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createGroup()
    .listGroups()
    .deleteGroup()
    )
}
