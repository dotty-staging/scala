


import util.matching._




object Test {

  def main(args: Array[String]) {
    replacementMatching
    groupsMatching
  }

  def replacementMatching {
    val regex = """\$\{(.+?)\}""".r
    val replaced = regex.replaceAllMatchDataIn("Replacing: ${main}. And another method: ${foo}.",
        (m: util.matching.Regex.Match) => {
      val identifier = m.group(1)
      identifier
    })
    assert(replaced == "Replacing: main. And another method: foo.")

    val regex2 = """\$\{(.+?)\}""".r
    val replaced2 = regex2.replaceAllIn("Replacing: ${main}. And then one more: ${bar}.", (s: String) => {
      "$1"
    })
    assert(replaced2 == "Replacing: main. And then one more: bar.")
  }

  def groupsMatching {
    val Date = """(\d+)/(\d+)/(\d+)""".r
    for (Regex.Groups(a, b, c) <- Date findFirstMatchIn "1/1/2001 marks the start of the millenium. 31/12/2000 doesn't.") {
      assert(a == "1")
      assert(b == "1")
      assert(c == "2001")
    }
    for (Regex.Groups(a, b, c) <- (Date findAllIn "1/1/2001 marks the start of the millenium. 31/12/2000 doesn't.").matchData) {
      assert(a == "1" || a == "31")
      assert(b == "1" || b == "12")
      assert(c == "2001" || c == "2000")
    }
  }

}
