package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateDeleteClients extends CommonSimulation {

  setUp("CRUD clients", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createClient()
    .listClients()
    .deleteClient())

}
