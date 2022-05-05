package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateRoles extends CommonSimulation {

  setUp("Create and List Roles", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .listRoles()
    .createRoles())

}
