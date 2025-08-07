//> using javaOpt -Dneeds.to.fork

object Test {

  //println("java.library.path=" + System.getProperty("java.library.path"))

  val userNativelib = System.getenv("scala_test_nativelib") // set to 'natives' for freshly built binary on linux. See mkLibNatives.sh
  val os = System.getProperty("os.name")
  val arch = System.getProperty("os.arch")
  var libName = ""

  if (userNativelib == null) {
    libName = (os, arch) match {
      case ("Mac OS X", "aarch64") =>
        "natives-arm"
      case ("Mac OS X", "x86_64") =>
        "natives-x86"
      case _ =>
         val wordSize = System.getProperty("sun.arch.data.model", "32")
        "natives-" + wordSize
    }
  } else {
    libName=userNativelib
  }

  System.loadLibrary(libName)

  @native
  def sayHello(s: String): String = null

  def main(args: Array[String]): Unit = {
    val s = sayHello("Scala is great!")
    println("Invocation returned \"" + s + "\"")
  }
}
