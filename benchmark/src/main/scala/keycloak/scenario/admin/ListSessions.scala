package keycloak.scenario.admin

import keycloak.scenario.{CommonSimulation, KeycloakScenarioBuilder}


class ListSessions extends CommonSimulation {

  setUp("List client and user sessions", new KeycloakScenarioBuilder()
    .adminCliToken()
    .getClientSessionStats()
    .getClientUUID()
    .getUserSessionsForClient()
    .getUserUUID()
    .getUserSessionsForUser()
  )
}
