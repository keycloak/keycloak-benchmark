package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateDeleteClientScopes extends CommonSimulation {

  setUp("Create, List and Delete client scopes", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createClientScopes()
    .listClientScopes()
    .deleteClientScopes()
  )
}
