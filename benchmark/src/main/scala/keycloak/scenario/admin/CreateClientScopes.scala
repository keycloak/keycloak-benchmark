package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateClientScopes extends CommonSimulation {

  setUp("Create and List client scopes", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createClientScope()
    .listClientScopes()
  )
}
