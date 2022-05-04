package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class CreateDeleteClients extends CommonSimulation {

  setUp("Create, List and Delete clients", new KeycloakScenarioBuilder()
    .serviceAccountToken()
    .createClient()
    .listClients()
    .deleteClient())

}
