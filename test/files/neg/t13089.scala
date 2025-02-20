//> using options -Werror

class T {
  def t1 = "" == Some("").getOrElse(None) // used to warn incorrectly, because a RefinementClassSymbol is unrelated to String

  def a: T with Serializable = null
  def b: Serializable with T = null
  def t2 = "" == a // warn
  def t3 = "" == b // no warn; on intersection types, the implementation currently picks the first parent
}
