//> using options -Xlint:implicit-recursion -Werror

import language.implicitConversions

object X {
  implicit class Elvis[A](alt: => A) { def ?:(a: A): A = if (a ne null) a else alt } // warn
}
object Y {
  implicit def f[A](a: A): String = if (a ne null) a else "nope" // warn
}
object Z {
  implicit class StringOps(val s: String) extends AnyVal {
    def crazy: String = s.reverse
    def normal: String = s.crazy.crazy // nowarn value class
    def join(other: String): String = crazy + other.crazy // nowarn
  }
}
object ZZ {
  implicit class StringOps(s: String) {
    def crazy: String = s.reverse
    def normal: String = s.crazy.crazy // warn
    def join(other: String): String = crazy + other.crazy // nowarn
  }
}
