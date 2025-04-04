object O {
  def tooManyArgs(s: String, i: Int): Unit = ()

  tooManyArgs(math.floor("not a num"))
}