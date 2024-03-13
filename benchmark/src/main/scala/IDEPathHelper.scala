
import io.gatling.shared.util.PathHelper.RichPath

import java.nio.file.{Path, Paths}

object IDEPathHelper {

  implicit class RichPath(path: Path) {
    def /(other: String): Path = path.resolve(other)
  }

  val gatlingConfUrl: Path = Paths.get(getClass.getClassLoader.getResource("gatling.conf").toURI)
  val projectRootDir = gatlingConfUrl.getParent.getParent.getParent.getParent

  val mavenSourcesDirectory = projectRootDir / "src" / "main" / "scala"
  val mavenResourcesDirectory = projectRootDir / "src" / "main" / "resources"
  val mavenTargetDirectory = projectRootDir / "target"
  val mavenBinariesDirectory = mavenTargetDirectory / "classes"

  val dataDirectory = mavenResourcesDirectory / "data"
  val bodiesDirectory = mavenResourcesDirectory / "bodies"

  val recorderOutputDirectory = mavenSourcesDirectory
  val resultsDirectory = mavenTargetDirectory / "gatling"

  val recorderConfigFile = mavenResourcesDirectory / "recorder.conf"
}
