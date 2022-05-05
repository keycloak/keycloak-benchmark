package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateGroups extends CommonSimulation {

  setUp("Create and List Groups", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createGroups()
    .listGroups())
}
