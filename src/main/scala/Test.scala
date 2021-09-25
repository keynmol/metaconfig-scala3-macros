package metaconfig.generic

case class Simple(world: String, test: Option[Int], l: List[Int])
case class HelloConfig(
  verbose: Boolean = false,
  name: String = "Susan"
)
object HelloConfig {
  lazy val default = HelloConfig()
  given Surface[HelloConfig] = metaconfig.generic.deriveSurface[HelloConfig]
  given metaconfig.ConfDecoder[HelloConfig] = metaconfig.generic.deriveDecoder[HelloConfig](default)
  given metaconfig.ConfEncoder[HelloConfig] = metaconfig.generic.deriveEncoder[HelloConfig]
}
@main def hello = 
  // println(deriveEncoder[Simple].write(Simple("hello", Some(5), Nil)))
  given Surface[Simple] = deriveSurface[Simple]
  val dec = deriveDecoder[Simple](Simple("what", None, Nil))
  val cliTest = metaconfig.Conf.parseCliArgs[HelloConfig](
    List("--name", "Amelie")
  ).andThen(_.as[HelloConfig]).get
  println(cliTest)
