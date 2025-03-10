//> using options -Wunused:patvars -Werror

case class A(x: Int, y: Int)

object Main {
  for {
    a <- List.empty[A]
    A(x, y) = a
  } yield x + y

  private val A(x, y) = A(42, 27) // nowarn for canonical name
  private val A(w, z) = A(42, 27) // warn
  private[this] val A(q, r) = A(42, 27) // warn
  def W = w
  def Q = q
}

class C {
  def f(x: Any) =
    x match {
      case x: String => // nowarn because x is not a new reference but an alias
      case _ =>
    }
  def g(x: Any) =
    (x: @unchecked) match {
      case x: String => // nowarn because x is not a new reference but an alias
      case _ =>
    }
}

final class ArrayOps[A](private val xs: Array[A]) extends AnyVal {
  def f =
    (xs: Array[_]) match {
      case xs =>
    }
}

class Publix {
  val A(w, z) = A(42, 27) // nowarn if an accessor is neither private nor local
}
