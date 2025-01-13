import scala.tools.partest.ReplTest

object Test extends ReplTest {
  override def code =
    """import annotation._, scala.util.chaining._
      |class ann(x: Int = 1, y: Int = 2) extends Annotation
      |class naa(x: Int = 1, y: Int = 2) extends Annotation {
      |  def this(s: String) = this(1, 2)
      |}
      |class mul(x: Int = 1, y: Int = 2)(z: Int = 3, zz: Int = 4) extends Annotation
      |class kon(x: Int = 1, y: Int = 2) extends ConstantAnnotation
      |class rann(x: Int = 1.tap(println), y: Int) extends Annotation
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
      |
      |  @kon(y = 22) def m11 = 1
      |  @kon(11) def m12 = 1
      |}
      |:power
      |println(typeOf[C].members.toList.filter(_.name.startsWith("m")).sortBy(_.name).map(_.annotations).mkString("\n"))
      |val i6 = typeOf[C].member(TermName("m6")).annotations.head
      |i6.constructorSymbol(global.typer.typed).paramss
      |val i11 = typeOf[C].member(TermName("m11")).annotations.head
      |i11.assocs
      |i11.assocsWithDefaults
      |val i3 = typeOf[C].member(TermName("m3")).annotations.head
      |i3.args.map(_.tpe)
      |i3.args.map(i3.argIsDefault)
      |// ordinary named/default args when using annotation class in executed code
      |new rann(y = 2.tap(println)); () // prints 2, then the default 1
      |@rann(y = {new rann(y = 2.tap(println)); 2}) class r1
      |println(typeOf[r1].typeSymbol.annotations.head.args)
      |""".stripMargin
}
