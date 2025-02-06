trait Inner {
  def f(): Outer
}

class Outer(val o: Set[Inner]) {
  def this() = this(Set(1).map{
    case k => new Inner {
      def f(): Outer = Outer.this
    }
  })
}

object Test {
  def main(args: Array[String]): Unit = {
    val outer = new Outer()
    val o = outer.o
    assert(o.sizeIs == 1)
    val inner = o.head

    /* Was:
     * java.lang.AbstractMethodError: Receiver class Outer$$anonfun$$lessinit$greater$1$$anon$1
     * does not define or inherit an implementation of the resolved method
     * 'abstract Outer f()' of interface Inner.
     * Selected method is 'abstract Outer Outer$$anonfun$$lessinit$greater$1$$anon$1.f()'.
     */
    assert(inner.f() eq outer)
  }
}
