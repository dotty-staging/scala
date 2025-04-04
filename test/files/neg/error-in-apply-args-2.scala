object O {
  def missingArgs(d: Double, s: String): Unit = ()

  missingArgs(math.floor("not a num"))
}