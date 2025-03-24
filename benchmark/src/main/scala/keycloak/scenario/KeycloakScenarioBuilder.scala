package keycloak.scenario

import io.gatling.commons.validation.Validation
import io.gatling.core.Predef._
import io.gatling.core.session.Session
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import keycloak.Utils
import keycloak.Utils.randomUUID
import keycloak.scenario.KeycloakScenarioBuilder.{ADMIN_ENDPOINT, CODE_PATTERN, LOGIN_ENDPOINT, LOGOUT_ENDPOINT, TOKEN_ENDPOINT, UI_HEADERS, REALMS_ENDPOINT, MASTER_REALM_TOKEN_ENDPOINT, downCounterAboveZero}
import org.keycloak.benchmark.Config

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationDouble
import scala.util.Random

/**
 * @author <a href="mailto:mstrukel@redhat.com">Marko Strukelj</a>
 */
object KeycloakScenarioBuilder {

  private val BASE_URL = "#{keycloakServer}/realms/#{realm}"
  private val LOGIN_ENDPOINT = BASE_URL + "/protocol/openid-connect/auth"
  private val LOGOUT_ENDPOINT = BASE_URL + "/protocol/openid-connect/logout"
  private val TOKEN_ENDPOINT = BASE_URL + "/protocol/openid-connect/token"
  private val MASTER_REALM_TOKEN_ENDPOINT = "#{keycloakServer}/realms/master/protocol/openid-connect/token"
  private val REALMS_ENDPOINT = "#{keycloakServer}/admin/realms"
  private val ADMIN_ENDPOINT = "#{keycloakServer}/admin/realms/#{realm}"
  private val CODE_PATTERN = "code="

  // Specify defaults for http requests
  private val UI_HEADERS = Map(
    "Accept" -> "text/html,application/xhtml+xml,application/xml",
    "Accept-Encoding" -> "gzip, deflate",
    "Accept-Language" -> "en-US,en;q=0.5",
    "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")

  val registerAndLogoutScenario: KeycloakScenarioBuilder = new KeycloakScenarioBuilder()
    .openLoginPageWithRegistrationLink(true)
    .browserOpensRegistrationPage()
    .userThinkPause()
    .browserPostsRegistrationDetails()
    .userThinkPause()
    .randomLogout()

  def downCounterAboveZero(session: Session, attrName: String): Validation[Boolean] = {
    val missCounter = session.attributes.get(attrName) match {
      case Some(result) => result.asInstanceOf[AtomicInteger]
      case None => new AtomicInteger(0)
    }
    missCounter.getAndDecrement() > 0
  }
}

class KeycloakScenarioBuilder {

  var chainBuilder: ChainBuilder = exec(s => {

    val serverUrl = Config.serverUrisList.iterator().next()
    val realmIndex = String.valueOf(Random.nextInt(Config.numOfRealms))
    val clientIndex = String.valueOf(Random.nextInt(Config.numClientsPerRealm))
    val userIndex = String.valueOf(Random.nextInt(Config.numUsersPerRealm) + Config.userIndexOffset)
    var realmName = Config.realmPrefix.concat(realmIndex)

    if (Config.realmName != null) {
      realmName = Config.realmName
    }

    var userName = Config.userNamePrefix.concat(userIndex)

    if (Config.userName != null) {
      userName = Config.userName
    }

    var userPassword = Config.userPasswordPrefix.concat(userIndex).concat(Config.userPasswordSuffix)

    if (Config.userPassword != null) {
      userPassword = Config.userPassword
    }

    var redirectUri = serverUrl.stripSuffix("/").concat("/realms/").concat(realmName).concat("/account")

    if (Config.clientRedirectUrl != null) {
      redirectUri = Config.clientRedirectUrl
    }

    var clientId = "client-".concat(clientIndex)

    if (Config.clientId != null) {
      clientId = Config.clientId
    }

    var clientSecret = "client-".concat(clientIndex).concat("-secret")

    if (Config.clientSecret != null) {
      clientSecret = Config.clientSecret
    }

    var scope = Config.scope

    if (scope == null) {
      scope = "openid profile"
    } else {
      scope = scope.trim().replace(',', ' ')
    }

    s.setAll("keycloakServer" -> serverUrl,
      "state" -> randomUUID(),
      "wrongPasswordCount" -> new AtomicInteger(Config.badLoginCount),
      "realm" -> realmName,
      "firstName" -> "",
      "lastName" -> "",
      "email" -> "",
      "username" -> userName,
      "password" -> userPassword,
      "clientId" -> clientId,
      "clientSecret" -> clientSecret,
      "redirectUri" -> redirectUri,
      "adminUsername" -> Config.adminUsername,
      "adminPassword" -> Config.adminPassword,
      "scope" -> scope
    )
  })
    .exitHereIfFailed

  def build(): ChainBuilder = {
    chainBuilder
  }

  def userThinkPause(): KeycloakScenarioBuilder = {
    val min = Config.userThinkTime * 0.2
    chainBuilder = chainBuilder.pause(min.seconds, Config.userThinkTime.seconds)
    this
  }

  def openLoginPage(pauseAfter: Boolean): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser to Log In Endpoint")
        .get(LOGIN_ENDPOINT)
        .headers(UI_HEADERS)
        .queryParam("login", "true")
        .queryParam("response_type", "code")
        .queryParam("client_id", "#{clientId}")
        .queryParam("state", "#{state}")
        .queryParam("redirect_uri", "#{redirectUri}")
        .queryParam("scope", "#{scope}")
        .check(status.is(200),
          regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("login-form-uri")))
      // if already logged in the check will fail with:
      // status.find.is(200), but actually found 302
      // The reason is that instead of returning the login page we are immediately redirected to the app that requested authentication
      .exitHereIfFailed
    if (pauseAfter) {
      userThinkPause()
    }
    this
  }

  def openLoginPageWithRegistrationLink(pauseAfter: Boolean): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser to Log In Endpoint")
        .get(LOGIN_ENDPOINT)
        .headers(UI_HEADERS)
        .queryParam("login", "true")
        .queryParam("response_type", "code")
        .queryParam("client_id", "#{clientId}")
        .queryParam("state", "#{state}")
        .queryParam("redirect_uri", "#{redirectUri}")
        .queryParam("scope", "#{scope}")
        .check(status.is(200),
          regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("login-form-uri"),
          regex("href=\"(/auth)?(/realms/[^\"]*/login-actions/registration[^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("registration-link")))
      // if already logged in the check will fail with:
      // status.find.is(200), but actually found 302
      // The reason is that instead of returning the login page we are immediately redirected to the app that requested authentication
      .exitHereIfFailed
    if (pauseAfter) {
      userThinkPause()
    }
    this
  }

  def browserPostsWrongCredentials(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .asLongAs(s => downCounterAboveZero(s, "wrongPasswordCount")) {
        val c = exec(http("Browser posts wrong credentials")
          .post("#{login-form-uri}")
          .headers(UI_HEADERS)
          .formParam("username", "#{username}")
          .formParam("password", _ => Utils.randomString(10))
          .formParam("login", "Log in")
          .check(status.is(200), regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("login-form-uri")))
          .exitHereIfFailed

        // make sure to call the right version of thinkPause - one that takes chainBuilder as argument
        // - because this is a nested chainBuilder - not the same as chainBuilder field
        thinkPause(c)
      }
    this
  }

  def thinkPause(builder: ChainBuilder): ChainBuilder = {
    val max = Config.userThinkTime * 0.2
    builder.pause(Config.userThinkTime.seconds, max.seconds)
  }

  def loginUsernamePassword(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser posts correct credentials")
        .post("#{login-form-uri}")
        .headers(UI_HEADERS)
        .formParam("username", "#{username}")
        .formParam("password", "#{password}")
        .formParam("login", "Log in")
        .check(
          status.is(302), header("Location").saveAs("login-redirect"),
          header("Location").transform(t => {
            val codeStart = t.indexOf(CODE_PATTERN)
            if (codeStart == -1) {
              return null
            }
            t.substring(codeStart + CODE_PATTERN.length, t.length())
          }).notNull.saveAs("code")
        ))
      .exitHereIfFailed
    this
  }

  def exchangeCode(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Exchange Code")
        .post(TOKEN_ENDPOINT)
        .headers(UI_HEADERS)
        .formParam("grant_type", "authorization_code")
        .formParam("client_id", "#{clientId}")
        .formParam("client_secret", "#{clientSecret}")
        .formParam("redirect_uri", "#{redirectUri}")
        .formParam("code", "#{code}")
        .check(
          status.is(200),
          jsonPath("$..id_token").find.saveAs("idToken"),
          jsonPath("$..access_token").find.saveAs("accessToken"),
          jsonPath("$..refresh_token").find.saveAs("refreshToken"),
          jsonPath("$..expires_in").find.saveAs("expiresIn"),
        )
      )
      .exec(session => session.removeAll("code"))
      .exitHereIfFailed
    this
  }

  def browserOpensRegistrationPage(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser to Registration Endpoint")
        .get("#{keycloakServer}#{registration-link}")
        .headers(UI_HEADERS)
        .check(
          status.is(200),
          regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("registration-form-uri"))
      )
      .exitHereIfFailed
    this
  }

  def browserPostsRegistrationDetails(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser posts registration details")
        .post("#{registration-form-uri}")
        .headers(UI_HEADERS)
        .formParam("firstName", "#{firstName}")
        .formParam("lastName", "#{lastName}")
        .formParam("email", "#{email}")
        .formParam("username", "#{username}")
        .formParam("password", "#{password}")
        .formParam("password-confirm", "#{password}")
        .check(status.is(302), header("Location").saveAs("login-redirect")))
      .exitHereIfFailed
    this
  }

  def logout(isRandom: Boolean): KeycloakScenarioBuilder = {
    userThinkPause()
    if (isRandom) {
      return randomLogout()
    } else {
      chainBuilder = chainBuilder.exec(logout())
    }
    this
  }

  private def logout(): ChainBuilder = {
    exec(http("Browser logout")
      .get(LOGOUT_ENDPOINT)
      .headers(UI_HEADERS)
      .queryParam("client_id", "#{clientId}")
      .queryParam("post_logout_redirect_uri", "#{redirectUri}")
      .queryParam("id_token_hint", "#{idToken}")
      .check(status.is(302), header("Location").is("#{redirectUri}")))
    .exec(session => session.removeAll("idToken", "redirectUri"))
  }

  def randomLogout(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .randomSwitch(
        Config.logoutPercentage -> exec(logout())
      )
    this
  }

  def clientCredentialsGrant(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Client credentials grant type")
        .post(TOKEN_ENDPOINT)
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", "#{clientId}")
        .formParam("client_secret", "#{clientSecret}")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def serviceAccountToken(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(getServiceAccountTokenExec())
    this
  }

  private def getServiceAccountTokenExec(): ChainBuilder = {
    // Store the current timestamp BEFORE it's actually sent (as we don't know how long the request will take or if it will succeed at all)
    exec(_.set("requestEpoch", System.currentTimeMillis()))
    .exec(http("Get service account token")
      .post(TOKEN_ENDPOINT)
      .formParam("grant_type", "client_credentials")
      .formParam("client_id", "gatling")
      .formParam("client_secret", "#{clientSecret}")
      .check(
        jsonPath("$..access_token").find.saveAs("token"),
        jsonPath("$..expires_in").find.saveAs("expiresIn")
      ))
      .exitHereIfFailed
      .exec(s => {
        s.set("accessTokenRefreshTime", s.attributes("requestEpoch"))
      })
  }

  def openHomePage(pauseAfter: Boolean): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Open Home Page #{keycloakServer}")
        .get("#{keycloakServer}/")
        .headers(UI_HEADERS)
        .check(status.is(200)))
      .exitHereIfFailed
    if (pauseAfter) {
      userThinkPause()
    }
    this
  }

  //Roles
  def listRoles(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List Roles")
        .get(ADMIN_ENDPOINT + "/roles")
        .header("Authorization", "Bearer #{token}")
        .queryParam("first", 0)
        .queryParam("max", 2)
        .check(
          status.is(200),
          jsonPath("$[*]").count.lte(2)))
      .exitHereIfFailed
    this
  }

  def createRole(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(_.set("createdRoleId", randomUUID()))
      .exec(http("Create Role")
        .post(ADMIN_ENDPOINT + "/roles")
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .body(StringBody("""{ "name" : "#{createdRoleId}" }"""))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("roleLocation")))
      .exec(session => session.removeAll("createdRoleId"))
      .exitHereIfFailed
    this
  }

  def deleteRole(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete Role")
        .delete("#{roleLocation}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(204)))
      .exec(session => session.removeAll("roleLocation"))
      .exitHereIfFailed
    this
  }

  //Groups
  def listGroups(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List Groups")
        .get(ADMIN_ENDPOINT + "/groups")
        .header("Authorization", "Bearer #{token}")
        .queryParam("first", 0)
        .queryParam("max", 2)
        .check(
          status.is(200),
          jsonPath("$[*]").count.lte(2)))
      .exitHereIfFailed
    this
  }

  def createGroup(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(_.set("createdGroupId", randomUUID()))
      .exec(http("Create Group")
        .post(ADMIN_ENDPOINT + "/groups")
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .body(StringBody("""{ "name" : "#{createdGroupId}" }"""))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("groupLocation")))
      .exec(session => session.removeAll("createdGroupId"))
      .exitHereIfFailed
    this
  }

  def deleteGroup(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete Group")
        .delete("#{groupLocation}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(204)))
      .exec(session => session.removeAll("groupLocation"))
      .exitHereIfFailed
    this
  }

  def adminCliToken(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      // Store the current timestamp BEFORE it's actually sent (as we don't know how long the request will take or if it will succeed at all)
      .exec(_.set("requestEpoch", System.currentTimeMillis()))
      .exec(http("Get admin-cli token")
        .post(MASTER_REALM_TOKEN_ENDPOINT)
        .formParam("grant_type", "password")
        .formParam("client_id", "admin-cli")
        .formParam("username", "#{adminUsername}")
        .formParam("password", "#{adminPassword}")
        .check(
          jsonPath("$..access_token").find.saveAs("token"),
          jsonPath("$..expires_in").find.saveAs("expiresIn")
        ))
        .exitHereIfFailed
        .exec(s => {
          s.set("accessTokenRefreshTime", s.attributes("requestEpoch"))
        })
    this
  }

  //Realms
  def createRealm(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(_.set("createdRealmId", randomUUID()))
      .exec(http("Create realm")
        .post(REALMS_ENDPOINT)
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .body(StringBody("""{"id":"#{createdRealmId}","realm":"#{createdRealmId}","enabled": true}"""))
        .check(status.is(201))
        .check(header("Location").notNull))
      .exitHereIfFailed
    this
  }

  def deleteRealm(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete realm")
        .delete(REALMS_ENDPOINT + "/#{createdRealmId}")
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .check(status.is(204)))
      .exec(session => session.removeAll("createdRealmId"))
      .exitHereIfFailed
    this
  }

  //Client Scopes
  def createClientScope(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(_.set("createdClientScopeId", randomUUID()))
      .exec(http("Create client scopes")
        .post(ADMIN_ENDPOINT + "/client-scopes")
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .body(StringBody(
          """ {"attributes":{"display.on.consent.screen":"true","include.in.token.scope":"true"},
            | "name":"#{createdClientScopeId}","protocol":"openid-connect"} """.stripMargin))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("clientScopeLocation")))
      .exec(session => session.removeAll("createdClientScopeId"))
      .exitHereIfFailed
    this
  }

  def listClientScopes(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List client Scopes")
        .get(ADMIN_ENDPOINT + "/client-scopes")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def deleteClientScope(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete client scope")
        .delete("#{clientScopeLocation}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(204)))
      .exec(session => session.removeAll("clientScopeLocation"))
      .exitHereIfFailed
    this
  }

  //Clients
  def createClient(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Create client")
        .post(ADMIN_ENDPOINT + "/clients")
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .body(StringBody("{}"))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("clientLocation")))
      .exitHereIfFailed
    this
  }

  def listClients(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List clients")
        .get(ADMIN_ENDPOINT + "/clients")
        .header("Authorization", "Bearer #{token}")
        .queryParam("max", 2)
        .check(
          status.is(200),
          jsonPath("$[*]").count.lte(2)))
      .exitHereIfFailed
    this
  }

  def deleteClient(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete client")
        .delete("#{clientLocation}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(204)))
      .exec(session => session.removeAll("clientLocation"))
      .exitHereIfFailed
    this
  }

  def getClientUUID(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List Clients and get a Client UUID")
        .get(ADMIN_ENDPOINT + "/clients?clientId=#{clientId}")
        .header("Authorization", "Bearer #{token}")
        .queryParam("first",0)
        .queryParam("max", 1)
        .check(
          status.is(200))
        .check(jsonPath("$[0].id").notNull.saveAs("clientUUID"))
      )
      .exitHereIfFailed
    this
  }

  def viewPagesOfUsers(pageSize: Int, numberOfPages: Int): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .repeat(numberOfPages, "page") {
        exec(session => {
          session.set("max", pageSize)
            .set("first", session("page").as[Int] * pageSize)
        })
          .doIf(s => needTokenRefresh(s)) {
            getServiceAccountTokenExec()
          }
          .exec(http("#{realm}/users?first=#{first}&max=#{max}")
            .get(ADMIN_ENDPOINT + "/users")
            .header("Authorization", "Bearer #{token}")
            .queryParam("first", "#{first}")
            .queryParam("max", "#{max}")
            .check(status.is(200)))
          .exitHereIfFailed
      }
    this
  }

  def createUser(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .feed(Iterator.continually(Map("username" -> randomUUID())))
      .exec(http("Create user")
        .post(ADMIN_ENDPOINT + "/users")
        .header("Authorization", "Bearer #{token}")
        .header("Content-Type", "application/json")
        .body(StringBody("""{"username":"#{username}"}"""))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("userLocation")))
      .exitHereIfFailed
    this
  }

  def listUsers(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
          .exec(http("List Users")
            .get(ADMIN_ENDPOINT + "/users")
            .header("Authorization", "Bearer #{token}")
            .queryParam("first", 0)
            .queryParam("max", 2)
            .check(
              status.is(200),
              jsonPath("$[*]").count.lte(2)))
          .exitHereIfFailed
    this
  }

  def getUserUUID(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List Users and get a User UUID")
        .get(ADMIN_ENDPOINT + "/users?username=#{username}&exact=true")
        .header("Authorization", "Bearer #{token}")
        .queryParam("first", 0)
        .queryParam("max", 1)
        .check(
          status.is(200))
        .check(jsonPath("$[0].id").notNull.saveAs("userUUID"))
      )
      .exitHereIfFailed
    this
  }

  def deleteUser(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete User")
        .delete("#{userLocation}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(204)))
      .exec(session => session.removeAll("userLocation"))
      .exitHereIfFailed
    this
  }

  def joinGroup(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Join group")
        .put("#{userLocation}/groups/#{group-id}")
        .header("Authorization", "Bearer #{token}")
        .check(status.is(204)))
      .exitHereIfFailed
    this
  }

  def findGroup(groupName: String): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
        .exec(http("Find group")
          .get(ADMIN_ENDPOINT + "/groups")
          .header("Authorization", "Bearer #{token}")
          .header("Accept-Encoding", "application/json")
          .queryParam("search", groupName)
          .queryParam("max", "1")
          .check(status.is(200))
          .check(jsonPath("$[0].id").notNull.saveAs("group-id")))
      .exitHereIfFailed
    this
  }

  def getClientSessionStats(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("client-session-stats")
        .get(ADMIN_ENDPOINT + "/client-session-stats")
        .header("Authorization", "Bearer #{token}")
        .header("Accept-Encoding", "application/json")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def getUserSessionsForClient(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List user sessions for a client")
        .get(ADMIN_ENDPOINT + "/clients/#{clientUUID}/user-sessions")
        .header("Authorization", "Bearer #{token}")
        .header("Accept-Encoding", "application/json")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def getUserSessionsForUser(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List user sessions for a user")
        .get(ADMIN_ENDPOINT + "/users/#{userUUID}/sessions")
        .header("Authorization", "Bearer #{token}")
        .header("Accept-Encoding", "application/json")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def needTokenRefresh(sess: Session): Boolean = {
    val lastRefresh = sess("accessTokenRefreshTime").as[Long]

    // 5 seconds before expiry is time to refresh
    lastRefresh + sess("expiresIn").as[String].toInt * 1000 - 5000 < System.currentTimeMillis()
  }

  private def refreshToken(): ChainBuilder = {
    doIfOrElse(Config.refreshCloseHttpConnection) {
      // In the real world a token refresh will need to start a new HTTP connection before the request.
      // This simulates the behavior in the load test by closing the connection after the request, so that the next
      // request will need to create a new connection. This assumes that a scenario will issue multiple refreshes
      // in the scenario, or at lest have one more request after this refresh, like a logout.
      exec(http("RefreshTokenAndCloseHttpConnection")
        .post(TOKEN_ENDPOINT)
        .headers(UI_HEADERS)
        .formParam("grant_type", "refresh_token")
        .formParam("refresh_token", "#{refreshToken}")
        .formParam("client_id", "#{clientId}")
        .formParam("client_secret", "#{clientSecret}")
        .formParam("redirect_uri", "#{redirectUri}")
        .formParam("connection", "close")
        .check(
          status.is(200),
          jsonPath("$..id_token").find.saveAs("idToken"),
          jsonPath("$..access_token").find.saveAs("accessToken"),
          jsonPath("$..refresh_token").find.saveAs("refreshToken"),
          jsonPath("$..expires_in").find.saveAs("expiresIn"),
        )
      ).exitHereIfFailed
    } {
      exec(http("RefreshToken")
        .post(TOKEN_ENDPOINT)
        .headers(UI_HEADERS)
        .formParam("grant_type", "refresh_token")
        .formParam("refresh_token", "#{refreshToken}")
        .formParam("client_id", "#{clientId}")
        .formParam("client_secret", "#{clientSecret}")
        .formParam("redirect_uri", "#{redirectUri}")
        .check(
          status.is(200),
          jsonPath("$..id_token").find.saveAs("idToken"),
          jsonPath("$..access_token").find.saveAs("accessToken"),
          jsonPath("$..refresh_token").find.saveAs("refreshToken"),
          jsonPath("$..expires_in").find.saveAs("expiresIn"),
        )
      ).exitHereIfFailed
    }
  }

  def repeatRefresh(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .repeat(Config.refreshTokenCount, "refresh_i") {
        refreshToken().pause(Config.refreshTokenPeriod)
      }
    this
  }

  def basicGet(endpoint: String): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Hello")
        .get(endpoint)
        .header("Accept", "*/*")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }
}

