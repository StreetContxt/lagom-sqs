organization in ThisBuild := "lagom-sqs"
scalaVersion in ThisBuild := "2.11.8"

val slf4j = "org.slf4j" % "log4j-over-slf4j" % "1.7.21"
val akkaStreamSqs = "me.snov" %% "akka-stream-sqs" % "0.1.0"
val awsJavaSdk = "com.amazonaws" % "aws-java-sdk" % "1.11.98"
val scalaTest = "org.scalatest" %% "scalatest" % "3.0.1"
val lagomApi = "com.lightbend.lagom" %% "lagom-api" % "1.3.0"
val lagomApiJavaDsl = "com.lightbend.lagom" %% "lagom-javadsl-api" % "1.3.0"
val lagomApiScalaDsl = "com.lightbend.lagom" %% "lagom-scaladsl-api" % "1.3.0"
val lagomPersistenceCore = "com.lightbend.lagom" %% "lagom-persistence-core" % "1.3.0"
val lagomJavadslBroker = "com.lightbend.lagom" %% "lagom-javadsl-broker" % "1.3.0"
val lagomJavadslServer = "com.lightbend.lagom" %% "lagom-javadsl-server" % "1.3.0"
val lagomScaladslBroker = "com.lightbend.lagom" %% "lagom-scaladsl-broker" % "1.3.0"
val lagomScaladslServer = "com.lightbend.lagom" %% "lagom-scaladsl-server" % "1.3.0"
val elasticMq = "org.elasticmq" %% "elasticmq-rest-sqs" % "0.13.2"

val sqsProjects = Seq[Project](
  `sqs-client`,
  `sqs-client-javadsl`,
  `sqs-client-scaladsl`,
  `sqs-broker`,
  `sqs-broker-javadsl`,
  `sqs-broker-scaladsl`
)

lazy val root = (project in file("."))
  .settings(name := "lagom-sqs")
  .aggregate(sqsProjects.map(Project.projectToRef): _*)

lazy val `sqs-client` = (project in file("service/core/sqs/client"))
  .settings(name := "lagom-sqs-client")
  .settings(
    libraryDependencies ++= Seq(
      slf4j,
      akkaStreamSqs,
      awsJavaSdk,
      lagomApi,
      scalaTest % Test
    )
  )

lazy val `sqs-client-javadsl` = (project in file("service/javadsl/sqs/client"))
  .settings(
    name := "lagom-javadsl-sqs-client"
  )
  .settings(
    libraryDependencies ++= Seq(
      lagomApiJavaDsl
    )
  )
  .dependsOn(`sqs-client`)

lazy val `sqs-client-scaladsl` = (project in file("service/scaladsl/sqs/client"))
  .settings(name := "lagom-scaladsl-sqs-client")
  .settings(
    libraryDependencies ++= Seq(
      lagomApiScalaDsl
    )
  )
  .dependsOn(`sqs-client`)

lazy val `sqs-broker` = (project in file("service/core/sqs/server"))
  .settings(name := "lagom-sqs-broker")
  .settings(
    libraryDependencies ++= Seq(
      slf4j,
      akkaStreamSqs,
      awsJavaSdk,
      lagomApi,
      lagomPersistenceCore
    )
  )
  .dependsOn(`sqs-client`)

lazy val `sqs-broker-javadsl` = (project in file("service/javadsl/sqs/server"))
  .settings(name := "lagom-javadsl-sqs-broker")
  .settings(
    libraryDependencies ++= Seq(
      lagomApiJavaDsl,
      lagomJavadslBroker,
      lagomJavadslServer,
      scalaTest % Test,
      elasticMq % Test

)
  )
  .dependsOn(`sqs-broker`, `sqs-client-javadsl`)

lazy val `sqs-broker-scaladsl` = (project in file("service/scaladsl/sqs/server"))
  .settings(name := "lagom-scaladsl-sqs-broker")
  .settings(
    libraryDependencies ++= Seq(
      lagomScaladslBroker,
      lagomScaladslServer,
      scalaTest % Test,
      elasticMq % Test
    )
  )
  .dependsOn(`sqs-broker`, `sqs-client-scaladsl`)
