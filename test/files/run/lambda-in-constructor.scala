class Foo(val f: Int => Int)

class Bar(x: Int) extends Foo(y => x + y)

object Test {
  def main(args: Array[String]): Unit = {
    val bar = new Bar(5)
    assert(bar.f(6) == 11)
  }
}
