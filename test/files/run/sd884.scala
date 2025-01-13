import scala.tools.partest.ReplTest

object Test extends ReplTest {
  override def code =
    """import annotation.Annotation
      |class ann(x: Int = 0, y: Int = 0) extends Annotation
      |class naa(x: Int = 0, y: Int = 0) extends Annotation {
      |  def this(s: String) = this(1, 2)
      |}
      |class mul(x: Int = 0, y: Int = 0)(z: Int = 0, zz: Int = 0) extends Annotation
      |class C {
      |  val a = 1
      |  val b = 2
      |
      |  @ann(y = b, x = a) def m1 = 1
      |
      |  @ann(x = a) def m2 = 1
      |  @ann(y = b) def m3 = 1
      |
      |  @naa(a, b) def m4 = 1
      |  @naa(y = b, x = a) def m5 = 1
      |  @naa("") def m6 = 1
      |
      |  // warn, only first argument list is kept
      |  @mul(a, b)(a, b) def m7 = 1
      |  @mul(y = b)(a, b) def m8 = 1
      |  @mul(y = b, x = a)(zz = b) def m9 = 1
      |  @mul(y = b)(zz = b) def m10 = 1
      |}
      |:power
      |println(typeOf[C].members.toList.filter(_.name.startsWith("m")).sortBy(_.name).map(_.annotations).mkString("\n"))
      |val i = typeOf[C].member(TermName("m6")).annotations.head
      |i.constructorSymbol(global.typer.typed).paramss
      |""".stripMargin
}
