package keycloak.scenario

import io.gatling.commons.validation.Validation
import io.gatling.core.Predef._
import io.gatling.core.session.Session
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import keycloak.Utils
import keycloak.Utils.randomUUID
import keycloak.scenario.KeycloakScenarioBuilder.{ADMIN_ENDPOINT, CODE_PATTERN, LOGIN_ENDPOINT, LOGOUT_ENDPOINT, TOKEN_ENDPOINT, UI_HEADERS, REALMS_ENDPOINT, downCounterAboveZero}
import keycloak.scenario._private.AdminConsoleScenarioBuilder.DATE_FMT
import org.keycloak.benchmark.Config

import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.DurationDouble
import scala.util.Random

/**
 * @author <a href="mailto:mstrukel@redhat.com">Marko Strukelj</a>
 */
object KeycloakScenarioBuilder {

  val BASE_URL = "${keycloakServer}/realms/${realm}"
  val LOGIN_ENDPOINT = BASE_URL + "/protocol/openid-connect/auth"
  val LOGOUT_ENDPOINT = BASE_URL + "/protocol/openid-connect/logout"
  val TOKEN_ENDPOINT = BASE_URL + "/protocol/openid-connect/token"
  val REALMS_ENDPOINT = "${keycloakServer}/admin/realms"
  val ADMIN_ENDPOINT = "${keycloakServer}/admin/realms/${realm}"
  val CODE_PATTERN = "code="

  // Specify defaults for http requests
  val UI_HEADERS = Map(
    "Accept" -> "text/html,application/xhtml+xml,application/xml",
    "Accept-Encoding" -> "gzip, deflate",
    "Accept-Language" -> "en-US,en;q=0.5",
    "User-Agent" -> "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.8; rv:16.0) Gecko/20100101 Firefox/16.0")

  val ACCEPT_JSON = Map("Accept" -> "application/json")
  val ACCEPT_ALL = Map("Accept" -> "*/*")

  val registerAndLogoutScenario = new KeycloakScenarioBuilder()
    .openLoginPage(true)
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

  var chainBuilder = exec(s => {

    val serverUrl = Config.serverUrisList.iterator().next()
    val realmIndex = String.valueOf(Random.nextInt(Config.numOfRealms))
    val clientIndex = String.valueOf(Random.nextInt(Config.numClientsPerRealm))
    val userIndex = String.valueOf(Random.nextInt(Config.numUsersPerRealm))
    var realmName = Config.realmPrefix.concat(realmIndex)

    if (Config.realmName != null) {
      realmName = Config.realmName
    }

    var userName = "user-".concat(userIndex)

    if (Config.userName != null) {
      userName = Config.userName
    }

    var userPassword = "user-".concat(userIndex).concat("-password")

    if (Config.userPassword != null) {
      userPassword = Config.userPassword
    }

    var redirectUri = serverUrl.concat("/realms/").concat(realmName).concat("/account");

    if (Config.clientRedirectUrl != null) {
      redirectUri = Config.clientRedirectUrl;
    }

    var clientId = "client-".concat(clientIndex);

    if (Config.clientId != null) {
      clientId = Config.clientId;
    }

    var clientSecret = "client-".concat(clientIndex).concat("-secret");

    if (Config.clientSecret != null) {
      clientSecret = Config.clientSecret;
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
      "redirectUri" -> redirectUri
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
        .queryParam("client_id", "${clientId}")
        .queryParam("state", "${state}")
        .queryParam("redirect_uri", "${redirectUri}")
        .queryParam("scope", "openid profile")
        .check(status.is(200),
          regex("action=\"([^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("login-form-uri"),
          regex("href=\"/auth(/realms/[^\"]*/login-actions/registration[^\"]*)\"").find.transform(_.replaceAll("&amp;", "&")).saveAs("registration-link")))
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
        var c = exec(http("Browser posts wrong credentials")
          .post("${login-form-uri}")
          .headers(UI_HEADERS)
          .formParam("username", "${username}")
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
        .post("${login-form-uri}")
        .headers(UI_HEADERS)
        .formParam("username", "${username}")
        .formParam("password", "${password}")
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
        .formParam("client_id", "${clientId}")
        .formParam("client_secret", "${clientSecret}")
        .formParam("redirect_uri", "${redirectUri}")
        .formParam("code", "${code}")
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def browserOpensRegistrationPage(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Browser to Registration Endpoint")
        .get("${keycloakServer}${registration-link}")
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
        .post("${registration-form-uri}")
        .headers(UI_HEADERS)
        .formParam("firstName", "${firstName}")
        .formParam("lastName", "${lastName}")
        .formParam("email", "${email}")
        .formParam("username", "${username}")
        .formParam("password", "${password}")
        .formParam("password-confirm", "${password}")
        .check(status.is(302), header("Location").saveAs("login-redirect")))
      .exitHereIfFailed
    this
  }

  def logout(isRandom: Boolean): KeycloakScenarioBuilder = {
    userThinkPause()
    if (isRandom) {
      return randomLogout()
    } else {
      chainBuilder = chainBuilder.exec(logout)
    }
    this
  }

  private def logout(): ChainBuilder = {
    exec(http("Browser logout")
      .get(LOGOUT_ENDPOINT)
      .headers(UI_HEADERS)
      .queryParam("redirect_uri", "${redirectUri}")
      .check(status.is(302), header("Location").is("${redirectUri}")))
  }

  def randomLogout(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .randomSwitch(
        Config.logoutPercentage -> exec(logout)
      )
    this
  }

  def clientCredentialsGrant(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Client credentials grant type")
        .post(TOKEN_ENDPOINT)
        .formParam("grant_type", "client_credentials")
        .formParam("client_id", "${clientId}")
        .formParam("client_secret", "${clientSecret}")
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
      .formParam("client_secret", "${clientSecret}")
      .check(
        jsonPath("$..access_token").find.saveAs("token"),
        jsonPath("$..expires_in").find.saveAs("expiresIn")
      ))
      .exitHereIfFailed
      .exec(s => {
        s.set("accessTokenRefreshTime", s.attributes("requestEpoch"))
      })
  }

  def createRealm(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(_.set("createdRealmId", randomUUID()))
      .exec(http("Create realm")
        .post(REALMS_ENDPOINT)
        .header("Authorization", "Bearer ${token}")
        .header("Content-Type", "application/json")
        .body(StringBody("""{"id":"${createdRealmId}","realm":"${createdRealmId}","enabled": true}"""))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("location")))
      .exitHereIfFailed
    this
  }

  def createClient(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Create client")
        .post(ADMIN_ENDPOINT + "/clients")
        .header("Authorization", "Bearer ${token}")
        .header("Content-Type", "application/json")
        .body(StringBody("{}"))
        .check(status.is(201))
        .check(header("Location").notNull.saveAs("location")))
      .exitHereIfFailed
    this
  }

  def listClients(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("List clients")
        .get(ADMIN_ENDPOINT + "/clients")
        .header("Authorization", "Bearer ${token}")
        .queryParam("max", 2)
        .check(status.is(200)))
      .exitHereIfFailed
    this
  }

  def deleteClient(): KeycloakScenarioBuilder = {
    chainBuilder = chainBuilder
      .exec(http("Delete client")
        .delete("${location}")
        .header("Authorization", "Bearer ${token}")
        .check(status.is(204)))
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
          .exec(http("${realm}/users?first=${first}&max=${max}")
            .get(ADMIN_ENDPOINT + "/users")
            .header("Authorization", "Bearer ${token}")
            .queryParam("first", "${first}")
            .queryParam("max", "${max}")
            .check(status.is(200)))
          .exitHereIfFailed
      }
    this
  }

  def needTokenRefresh(sess: Session): Boolean = {
    val lastRefresh = sess("accessTokenRefreshTime").as[Long]

    // 5 seconds before expiry is time to refresh
    lastRefresh + sess("expiresIn").as[String].toInt * 1000 - 5000 < System.currentTimeMillis()
  }
}

