package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateDeleteRoles extends CommonSimulation {

  setUp("Create, List and Delete Roles", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createRoles()
    .listRoles()
    .deleteRoles()
  )
}
