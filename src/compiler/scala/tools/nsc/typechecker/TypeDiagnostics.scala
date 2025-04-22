/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc
package typechecker

import scala.annotation._
import scala.collection.mutable, mutable.ListBuffer
import scala.tools.nsc.Reporting.WarningCategory
import scala.util.chaining._
import scala.util.control.Exception.ultimately
import symtab.Flags._
import PartialFunction.{cond, condOpt}

/** An interface to enable higher configurability of diagnostic messages
 *  regarding type errors.  This is barely a beginning as error messages are
 *  distributed far and wide across the codebase.  The plan is to partition
 *  error messages into some broad groups and provide some mechanism for
 *  being more or less verbose on a selective basis.  Possible groups include
 *  such examples as
 *
 *    arity errors
 *    kind errors
 *    variance errors
 *    ambiguity errors
 *    volatility/stability errors
 *    implementation restrictions
 *
 *  And more, and there is plenty of overlap, so it'll be a process.
 *
 *  @author Paul Phillips
 */
trait TypeDiagnostics extends splain.SplainDiagnostics {
  _: Analyzer with StdAttachments =>

  import global._
  import definitions._

  /** For errors which are artifacts of the implementation: such messages
   *  indicate that the restriction may be lifted in the future.
   */
  def restrictionWarning(pos: Position, @unused unit: CompilationUnit, msg: String, category: WarningCategory, site: Symbol): Unit =
    runReporting.warning(pos, "Implementation restriction: " + msg, category, site)
  def restrictionError(pos: Position, @unused unit: CompilationUnit, msg: String): Unit =
    reporter.error(pos, "Implementation restriction: " + msg)

  /** A map of Positions to addendums - if an error involves a position in
   *  the map, the addendum should also be printed.
   */
  private val addendums = perRunCaches.newMap[Position, () => String]()
  private var isTyperInPattern = false

  /** Devising new ways of communicating error info out of
   *  desperation to work on error messages.  This is used
   *  by typedPattern to wrap its business so we can generate
   *  a sensible error message when things go south.
   */
  def typingInPattern[T](body: => T): T = {
    val saved = isTyperInPattern
    isTyperInPattern = true
    try body
    finally isTyperInPattern = saved
  }

  def setAddendum(pos: Position, msg: () => String) =
    if (pos != NoPosition)
      addendums(pos) = msg

  def withAddendum(pos: Position) = (_: String) + addendums.getOrElse(pos, () => "")()

  def decodeWithKind(name: Name, owner: Symbol): String = {
    val prefix = (
      if (name.isTypeName) "type "
      else if (owner.isPackageClass) "object "
      else "value "
    )
    prefix + name.decode
  }

  /** Does the positioned line assigned to t1 precede that of t2?
   */
  def posPrecedes(p1: Position, p2: Position) = p1.isDefined && p2.isDefined && p1.line < p2.line
  def linePrecedes(t1: Tree, t2: Tree) = posPrecedes(t1.pos, t2.pos)

  private object DealiasedType extends TypeMap {
    def apply(tp: Type): Type = tp match {
      // Avoid "explaining" that String is really java.lang.String,
      // while still dealiasing types from non-default namespaces.
      case TypeRef(pre, sym, args) if sym.isAliasType && !sym.isInDefaultNamespace =>
        mapOver(tp.dealias)
      case _ =>
        mapOver(tp)
    }
  }

  /** An explanatory note to be added to error messages
   *  when there's a problem with abstract var defs */
  def abstractVarMessage(sym: Symbol): String =
    if (sym.isSetter || sym.isGetter && sym.setterIn(sym.owner).exists)
      "\n(Note that variables need to be initialized to be defined)"
    else ""

  private def methodTypeErrorString(tp: Type) = tp match {
    case MethodType(params, resultType) =>
      params.map(_.defString).mkString("(", ",", s")$resultType")
    case tp => tp.toString
  }

  /**
   * [a, b, c] => "(a, b, c)"
   * [a, B]    => "(param1, param2)"
   * [a, B, c] => "(param1, ..., param2)"
   */
  final def exampleTuplePattern(names: List[Name]): String = {
    val arity = names.length
    val varPatternNames: Option[List[String]] = traverseOpt(names) {
      case name if nme.isVariableName(name) => Some(name.decode)
      case _                                => None
    }
    def parenthesize(a: String) = s"($a)"
    def genericParams = (Seq("param1") ++ (if (arity > 2) Seq("...") else Nil) ++ Seq(s"param$arity"))
    parenthesize(varPatternNames.getOrElse(genericParams).mkString(", "))
  }

  def alternatives(tree: Tree): List[Type] = tree.tpe match {
    case OverloadedType(pre, alternatives)  => alternatives map pre.memberType
    case _                                  => Nil
  }
  def alternativesString(tree: Tree) =
    alternatives(tree).map(x => "  " + methodTypeErrorString(x)).mkString("", " <and>\n", "\n")

  /** The symbol which the given accessor represents (possibly in part).
    * This is used for error messages, where we want to speak in terms
    * of the actual declaration or definition, not in terms of the generated setters
    * and getters.
    *
    * TODO: is it wise to create new symbols simply to generate error message? is this safe in interactive/resident mode?
    */
  def underlyingSymbol(member: Symbol): Symbol =
    if (!member.hasAccessorFlag || member.accessed == NoSymbol) member
    else if (!member.isDeferred) member.accessed
    else {
      val getter = if (member.isSetter) member.getterIn(member.owner) else member
      val flags  = if (getter.setterIn(member.owner) != NoSymbol) DEFERRED.toLong | MUTABLE else DEFERRED

      getter.owner.newValue(getter.name.toTermName, getter.pos, flags) setInfo getter.tpe.resultType
    }

  def treeSymTypeMsg(tree: Tree): String = {
    val sym               = tree.symbol
    def hasParams         = tree.tpe.paramSectionCount > 0
    def preResultString   = if (hasParams) ": " else " of type "

    def patternMessage    = "pattern " + tree.tpe.finalResultType + valueParamsString(tree.tpe)
    def exprMessage       = "expression of type " + tree.tpe
    def overloadedMessage =
      if (sym.isConstructor) s"multiple constructors for ${sym.owner.decodedName}${sym.idString} with alternatives:\n${alternativesString(tree)}"
      else s"overloaded method ${sym.decodedName} with alternatives:\n${alternativesString(tree)}"
    def moduleMessage     = "" + sym
    def defaultMessage    = moduleMessage + preResultString + tree.tpe
    def applyMessage      = defaultMessage + tree.symbol.locationString

    if (!tree.hasExistingSymbol) {
      if (isTyperInPattern) patternMessage
      else exprMessage
    }
    else if (sym.isOverloaded) overloadedMessage
    else if (sym.isModule) moduleMessage
    else if (sym.name == nme.apply) applyMessage
    else defaultMessage
  }

  def disambiguate(ss: List[String]) = ss match {
    case Nil      => Nil
    case s :: ss  => s :: (ss map { case `s` => "(some other)"+s ; case x => x })
  }

  // todo: use also for other error messages
  def existentialContext(tp: Type) = tp.skolemsExceptMethodTypeParams match {
    case Nil  => ""
    case xs   => " where " + (disambiguate(xs map (_.existentialToString)) mkString ", ")
  }

  def explainAlias(tp: Type) = {
    // Don't automatically normalize standard aliases; they still will be
    // expanded if necessary to disambiguate simple identifiers.
    val deepDealias = DealiasedType(tp)
    if (tp eq deepDealias) "" else {
      // A check against expansion being identical to original.
      val s = "" + deepDealias
      if (s == "" + tp) ""
      else "\n    (which expands to)  " + s
    }
  }

  /** Look through the base types of the found type for any which
   *  might have been valid subtypes if given conformant type arguments.
   *  Examine those for situations where the type error would have been
   *  eliminated if the variance were different.  In such cases, append
   *  an additional explanatory message.
   *
   *  TODO: handle type aliases better.
   */
  @nowarn("cat=lint-nonlocal-return")
  def explainVariance(found: Type, req: Type): String = {
    found.baseTypeSeq.toList foreach { tp =>
      if (tp.typeSymbol isSubClass req.typeSymbol) {
        val foundArgs = tp.typeArgs
        val reqArgs   = req.typeArgs
        val params    = req.typeConstructor.typeParams

        if (foundArgs.nonEmpty && foundArgs.length == reqArgs.length) {
          val relationships = map3(foundArgs, reqArgs, params){
            (arg, reqArg, param) =>
              def mkMsg(isSubtype: Boolean) = {
                val op      = if (isSubtype) "<:" else ">:"
                val suggest = if (isSubtype) "+" else "-"
                val reqsym  = req.typeSymbol
                def isJava  = reqsym.isJavaDefined
                def isScala = reqsym hasTransOwner ScalaPackageClass

                val explainFound = "%s %s %s%s, but ".format(
                  arg, op, reqArg,
                  // If the message involves a type from the base type sequence rather than the
                  // actual found type, we need to explain why we're talking about it.  Less brute
                  // force measures than comparing normalized Strings were producing error messages
                  // like "and java.util.ArrayList[String] <: java.util.ArrayList[String]" but there
                  // should be a cleaner way to do this.
                  if (found.dealiasWiden.toString == tp.dealiasWiden.toString) ""
                  else " (and %s <: %s)".format(found, tp)
                )
                val explainDef = {
                  val prepend = if (isJava) "Java-defined " else ""
                  "%s%s is %s in %s.".format(prepend, reqsym, param.variance, param)
                }
                // Don't suggest they change the class declaration if it's somewhere
                // under scala.* or defined in a java class, because attempting either
                // would be fruitless.
                val suggestChange = "\nYou may wish to " + (
                  if (isScala || isJava)
                    "investigate a wildcard type such as `_ %s %s`. (SLS 3.2.10)".format(op, reqArg)
                  else
                    "define %s as %s%s instead. (SLS 4.5)".format(param.name, suggest, param.name)
                )

                Some("Note: " + explainFound + explainDef + suggestChange)
              }
              // In these cases the arg is OK and needs no explanation.
              val conforms = (
                   (arg =:= reqArg)
                || ((arg <:< reqArg) && param.isCovariant)
                || ((reqArg <:< arg) && param.isContravariant)
              )
              val invariant = param.variance.isInvariant

              if (conforms)                             Some("")
              else if ((arg <:< reqArg) && invariant)   mkMsg(isSubtype = true)   // covariant relationship
              else if ((reqArg <:< arg) && invariant)   mkMsg(isSubtype = false)  // contravariant relationship
              else None // we assume in other cases our ham-fisted advice will merely serve to confuse
          }
          val messages = relationships.flatten
          // the condition verifies no type argument came back None
          if (messages.size == foundArgs.size)
            return messages.filterNot(_ == "").mkString("\n", "\n", "")
        }
      }
    }
    ""    // no elaborable variance situation found
  }

  // For found/required errors where AnyRef would have sufficed:
  // explain in greater detail.
  def explainAnyVsAnyRef(found: Type, req: Type): String = if (AnyRefTpe <:< req) notAnyRefMessage(found).pipe(msg => if (msg.isEmpty) "" else "\n" + msg) else ""

  def finalOwners(tpe: Type): Boolean = (tpe.prefix == NoPrefix) || recursivelyFinal(tpe)

  @tailrec
  final def recursivelyFinal(tpe: Type): Boolean = {
    val prefix = tpe.prefix
    if (prefix != NoPrefix) {
      if (prefix.typeSymbol.isFinal) {
        recursivelyFinal(prefix)
      } else {
        false
      }
    } else {
      true
    }
  }

  // TODO - figure out how to avoid doing any work at all
  // when the message will never be seen.  I though context.reportErrors
  // being false would do that, but if I return "<suppressed>" under
  // that condition, I see it.
  def builtinFoundReqMsg(found: Type, req: Type): String = {
    val foundWiden = found.widen
    val reqWiden = req.widen
    val sameNamesDifferentPrefixes =
      foundWiden.typeSymbol.name == reqWiden.typeSymbol.name &&
        foundWiden.prefix.typeSymbol != reqWiden.prefix.typeSymbol
    val easilyMistakable =
      sameNamesDifferentPrefixes &&
      !req.typeSymbol.isConstant &&
      finalOwners(foundWiden) && finalOwners(reqWiden) &&
      !found.typeSymbol.isTypeParameterOrSkolem && !req.typeSymbol.isTypeParameterOrSkolem

    if (easilyMistakable) {
      val longestNameLength = foundWiden.nameAndArgsString.length max reqWiden.nameAndArgsString.length
      val paddedFoundName = foundWiden.nameAndArgsString.padTo(longestNameLength, ' ')
      val paddedReqName = reqWiden.nameAndArgsString.padTo(longestNameLength, ' ')
      ";\n found   : " + (paddedFoundName + s" (in ${found.prefix.typeSymbol.fullNameString}) ") + explainAlias(found) +
       "\n required: " + (paddedReqName + s" (in ${req.prefix.typeSymbol.fullNameString}) ") + explainAlias(req)
    } else {
      def baseMessage = {
        ";\n found   : " + found.toLongString + existentialContext(found) + explainAlias(found) +
         "\n required: " + req + existentialContext(req) + explainAlias(req)
      }
      (withDisambiguation(Nil, found, req)(baseMessage)
        + explainVariance(found, req)
        + explainAnyVsAnyRef(found, req)
        )
    }
  }

  def foundReqMsg(found: Type, req: Type): String = {
    val errMsg = splainFoundReqMsg(found, req)
    if (errMsg.isEmpty) builtinFoundReqMsg(found, req) else errMsg
  }

  def typePatternAdvice(sym: Symbol, ptSym: Symbol) = {
    val clazz = if (sym.isModuleClass) sym.companionClass else sym
    val caseString =
      if (clazz.isCaseClass && (clazz isSubClass ptSym))
        ( clazz.caseFieldAccessors
          .map(_ => "_")    // could use the actual param names here
          .mkString(s"`case ${clazz.name}(", ",", ")`")
        )
      else
        "`case _: " + (clazz.typeParams match {
          case Nil  => "" + clazz.name
          case xs   => xs.map(_ => "_").mkString(s"${clazz.name}[", ",", "]")
        })+ "`"

    if (!clazz.exists) ""
    else "\nNote: if you intended to match against the class, try "+ caseString
  }

  case class TypeDiag(tp: Type, sym: Symbol) extends Ordered[TypeDiag] {
    // save the name because it will be mutated until it has been
    // distinguished from the other types in the same error message
    private val savedName = sym.name
    private var postQualifiedWith: List[Symbol] = Nil
    def restoreName()     = sym.name = savedName
    def modifyName(f: String => String): Unit = sym setName newTypeName(f(sym.name.toString))

    // functions to manipulate the name
    def preQualify()   = modifyName(trueOwner.fullName + "." + _)
    def postQualify()  = if (!(postQualifiedWith contains trueOwner)) {
      postQualifiedWith ::= trueOwner
      modifyName(s => s"$s(in $trueOwner)")
    }
    def typeQualify()  = if (sym.isTypeParameterOrSkolem) postQualify()
    def nameQualify()  = if (trueOwner.isPackageClass) preQualify() else postQualify()

    def trueOwner  = tp.typeSymbol.effectiveOwner
    def aliasOwner = tp.typeSymbolDirect.effectiveOwner

    def sym_==(other: TypeDiag)     = tp.typeSymbol == other.tp.typeSymbol
    def owner_==(other: TypeDiag)   = trueOwner == other.trueOwner
    def string_==(other: TypeDiag)  = tp.toString == other.tp.toString
    def name_==(other: TypeDiag)    = sym.name == other.sym.name

    def compare(other: TypeDiag) =
      if (this == other) 0
      else if (sym isLess other.sym) -1
      else 1

    override def toString = {
      """
      |tp = %s
      |tp.typeSymbol = %s
      |tp.typeSymbol.owner = %s
      |tp.typeSymbolDirect = %s
      |tp.typeSymbolDirect.owner = %s
      """.stripMargin.format(
        tp, tp.typeSymbol, tp.typeSymbol.owner, tp.typeSymbolDirect, tp.typeSymbolDirect.owner
      )
    }
  }
  /** This is tricky stuff - we need to traverse types deeply to
   *  explain name ambiguities, which may occur anywhere.  However
   *  when lub explosions come through it knocks us into an n^2
   *  disaster, see scala/bug#5580.  This is trying to perform the initial
   *  filtering of possibly ambiguous types in a sufficiently
   *  aggressive way that the state space won't explode.
   */
  private def typeDiags(locals: List[Symbol], types0: Type*): List[TypeDiag] = {
    val types   = types0.toList
    // If two different type diag instances are seen for a given
    // key (either the string representation of a type, or the simple
    // name of a symbol) then keep them for disambiguation.
    val strings = mutable.Map[String, Set[TypeDiag]]() withDefaultValue Set()
    val names   = mutable.Map[Name, Set[TypeDiag]]() withDefaultValue Set()

    val localsSet = locals.toSet

    def record(t: Type, sym: Symbol) = {
      if (!localsSet(sym)) {
        val diag = TypeDiag(t, sym)
        strings("" + t) += diag
        names(sym.name) += diag
      }
    }
    for (tpe <- types ; t <- tpe) {
      t match {
        case ConstantType(_)    => record(t, t.underlying.typeSymbol)
        case TypeRef(_, sym, _) => record(t, sym)
        case _                  => ()
      }
    }

    val collisions = strings.values ++ names.values filter (_.size > 1)
    collisions.flatten.toList
  }

  /** The distinct pairs from an ordered list. */
  private def pairs[T <: Ordered[T]](xs: Seq[T]): Seq[(T, T)] = {
    for (el1 <- xs ; el2 <- xs ; if el1 < el2) yield
      ((el1, el2))
  }

  /** Given any number of types, alters the name information in the symbols
   *  until they can be distinguished from one another: then executes the given
   *  code.  The names are restored and the result is returned.
   */
  def withDisambiguation[T](locals: List[Symbol], types: Type*)(op: => T): T = {
    val typeRefs = typeDiags(locals, types: _*)
    val toCheck  = pairs(typeRefs) filterNot { case (td1, td2) => td1 sym_== td2 }

    ultimately(typeRefs foreach (_.restoreName())) {
      for ((td1, td2) <- toCheck) {
        val tds = List(td1, td2)

        // If the types print identically, qualify them:
        //   a) If the dealiased owner is a package, the full path
        //   b) Otherwise, append (in <owner>)
        if (td1 string_== td2)
          tds foreach (_.nameQualify())

        // If they still print identically:
        //   a) If they are type parameters with different owners, append (in <owner>)
        //   b) Failing that, the best we can do is append "(some other)" to the latter.
        if (td1 string_== td2) {
          if (td1 owner_== td2)
            td2.modifyName("(some other)" + _)
          else
            tds foreach (_.typeQualify())
        }
      }
      // performing the actual operation
      op
    }
  }

  object checkDead {
    private def treeOK(tree: Tree) = {
      val isLabelDef = tree match { case _: LabelDef => true; case _ => false}
      tree.tpe != null && tree.tpe.typeSymbol == NothingClass && !isLabelDef
    }

    def apply(context: Context, tree: Tree): Tree = {
      if (settings.warnDeadCode.value && context.unit.exists && treeOK(tree) && !context.contextMode.inAny(ContextMode.SuppressDeadArgWarning))
        context.warning(tree.pos, "dead code following this construct", WarningCategory.WFlagDeadCode)
      tree
    }

    // The checkDead call from typedArg is more selective.
    def inMode(context: Context, mode: Mode, tree: Tree): Tree = if (mode.typingMonoExprByValue) apply(context, tree) else tree
  }

  object UnusedPrivates {
    val ignoreNames: Set[TermName] = Set(
      "readResolve", "readObject", "writeObject", "writeReplace"
    ).map(TermName(_))

    // Bind of pattern var was `x @ _`; also used for wildcard, e.g. `_ <- e`
    private def nowarn(tree: Bind): Boolean   = tree.hasAttachment[NoWarnAttachment.type]
    private def nowarn(tree: ValDef): Boolean = tree.hasAttachment[NoWarnAttachment.type]

    // ValDef was a PatVarDef `val P(x) = ???`
    private def wasPatVarDef(tree: ValDef): Boolean = tree.hasAttachment[PatVarDefAttachment.type]
    private def wasPatVarDef(sym: Symbol): Boolean = sym.hasAttachment[PatVarDefAttachment.type]
  }

  class UnusedPrivates extends Traverser {
    import UnusedPrivates.{ignoreNames, nowarn, wasPatVarDef}
    def isEffectivelyPrivate(sym: Symbol): Boolean = false // see REPL
    val defnTrees = ListBuffer.empty[MemberDef]
    val targets   = mutable.Set.empty[Symbol]
    val setVars   = mutable.Set.empty[Symbol]
    val treeTypes = mutable.Set.empty[Type]
    val params    = mutable.Set.empty[Symbol]
    val patvars   = ListBuffer.empty[Tree /*Bind|ValDef*/]
    val ignore    = mutable.Set.empty[Symbol] // nowarn

    val annots    = mutable.Set.empty[AnnotationInfo] // avoid revisiting annotations of symbols and types

    def recordReference(sym: Symbol): Unit = targets.addOne(sym)

    def checkNowarn(tree: Tree): Unit =
      tree match {
        case tree: Bind =>
          if (nowarn(tree)) ignore += tree.symbol
        case tree: ValDef =>
          if (nowarn(tree)) ignore += tree.symbol
        case _ =>
      }

    def qualifiesTerm(sym: Symbol) = (
      (sym.isModule || sym.isMethod || sym.isPrivateLocal || sym.isLocalToBlock || isEffectivelyPrivate(sym))
        && !nme.isLocalName(sym.name)
        && !sym.isParameter
        && !sym.isParamAccessor       // could improve this, but it's a pain
        && !sym.isEarlyInitialized    // lots of false positives in the way these are encoded
        && !(sym.isGetter && sym.accessed.isEarlyInitialized)
      )
    def qualifiesType(sym: Symbol) = !sym.isDefinedInPackage
    def qualifies(sym: Symbol) = (
      (sym ne null)
        && (sym.isTerm && qualifiesTerm(sym) || sym.isType && qualifiesType(sym))
      )
    def isExisting(sym: Symbol) = sym != null && sym.exists
    def addPatVar(t: Tree) = {
      checkNowarn(t)
      patvars += t
    }

    // so trivial that it never consumes params
    def isTrivial(rhs: Tree): Boolean =
      rhs.symbol == Predef_??? || rhs.tpe == null || rhs.tpe =:= NothingTpe || (rhs match {
        case Literal(_) => true
        case _          => isConstantType(rhs.tpe) || isSingleType(rhs.tpe) || rhs.isInstanceOf[This]
      })

    override def traverse(t: Tree): Unit = {
      t match {
        case t: ValDef if wasPatVarDef(t) => // include field excluded by qualifies test
          if (settings.warnUnusedPatVars)
            addPatVar(t)
        case t: MemberDef if qualifies(t.symbol) && !t.isErrorTyped =>
          val sym = t.symbol
          t match {
            case DefDef(_, _, _, vparamss, _, rhs) if !sym.isAbstract && !sym.isDeprecated && !sym.isMacro =>
              if (isSuppressed(sym)) return // ignore params and rhs of @unused def
              if (sym.isPrimaryConstructor)
                for (cpa <- sym.owner.constrParamAccessors if cpa.isPrivateLocal) params += cpa
              else if (sym.isSynthetic && sym.isImplicit) return
              else if (!sym.isConstructor && !sym.isVar && !isTrivial(rhs))
                for (vs <- vparamss; v <- vs) if (!isSingleType(v.symbol.tpe)) params += v.symbol
              if (sym.isGetter && wasPatVarDef(sym.accessed)) {
                if (settings.warnUnusedPatVars)
                  addPatVar(t)
              }
              else defnTrees += t
            case TypeDef(_, _, _, _) =>
              if (!sym.isAbstract && !sym.isDeprecated)
                defnTrees += t
            case _ =>
              defnTrees += t
          }
        case Match(selector, cases) =>
          // don't warn when a patvar redefines the selector ident: x match { case x: X => }
          // or extracts a single patvar named identically to the selector
          def allowVariableBindings(n: Name, pat: Tree): Unit =
            pat match {
              case Bind(`n`, _) => pat.updateAttachment(NoWarnAttachment)
              case Apply(_, _) | UnApply(_, _) => // really interested in args
                pat.filter(_.isInstanceOf[Bind]) match { // never nme.WILDCARD
                  case (bind @ Bind(`n`, _)) :: Nil => bind.updateAttachment(NoWarnAttachment) // one only
                  case _ =>
                }
              case _ =>
            }
          def allow(n: Name): Unit = cases.foreach(k => allowVariableBindings(n, k.pat))
          def loop(selector: Tree): Unit =
            selector match {
              case Ident(n) => allow(n)
              case Typed(expr, _) => loop(expr)
              case Select(This(_), n) => allow(n)
              case _ =>
            }
          loop(selector)
        case CaseDef(pat, _, _) if settings.warnUnusedPatVars && !t.isErrorTyped =>
          def allowVariableBindings(app: Apply, args: List[Tree]): Unit =
            treeInfo.dissectApplied(app).core.tpe match {
              case MethodType(ps, _) =>
                foreach2(ps, args) { (p, x) =>
                  x match {
                    case Bind(n, _) if p.name == n => x.updateAttachment(NoWarnAttachment)
                    case _ =>
                  }
                }
              case _ =>
            }
          pat.foreach {
            case app @ Apply(_, args) => allowVariableBindings(app, args)
            case b @ Bind(n, _) if n != nme.DEFAULT_CASE => addPatVar(b)
            case _ =>
          }
        case t: RefTree =>
          val sym = t.symbol
          if (isExisting(sym) && !currentOwner.hasTransOwner(sym) && !t.hasAttachment[ForAttachment.type])
            recordReference(sym)
        case Assign(lhs, _) if isExisting(lhs.symbol) => setVars += lhs.symbol
        case Function(ps, _) if !t.isErrorTyped =>
          for (p <- ps) {
            if (wasPatVarDef(p)) {
              if (settings.warnUnusedPatVars)
                addPatVar(p)
            }
            else {
              if (settings.warnUnusedParams && !p.symbol.isSynthetic) {
                checkNowarn(p)
                params += p.symbol
              }
            }
          }
        case treeInfo.Applied(fun, _, _)
        if t.hasAttachment[ForAttachment.type] && fun.symbol != null && isTupleSymbol(fun.symbol.owner.companion) =>
          return // ignore tupling of assignments
        case Literal(_) =>
          t.attachments.get[OriginalTreeAttachment].foreach(ota => traverse(ota.original))
        case tt: TypeTree =>
          tt.original match {
            case null =>
            case xo if xo ne tt => traverse(xo)
            case _ =>
          }
        case _ =>
      }

      def descend(annot: AnnotationInfo): Unit =
        if (!annots(annot)) {
          annots.addOne(annot)
          traverse(annot.original)
        }
      if ((t.tpe ne null) && t.tpe != NoType) {
        for (tp <- t.tpe if tp != NoType) if (!treeTypes(tp)) {
          // Include references to private/local aliases (which might otherwise refer to an enclosing class)
          val isAlias = {
            val td = tp.typeSymbolDirect
            td.isAliasType && (td.isLocalToBlock || td.isPrivate)
          }
          // Ignore type references to an enclosing class. A reference to C must be outside C to avoid warning.
          if (isAlias || !currentOwner.hasTransOwner(tp.typeSymbol)) tp match {
            case NoType | NoPrefix    =>
            case NullaryMethodType(_) =>
            case MethodType(_, _)     =>
            case SingleType(_, _)     =>
            case ConstantType(Constant(k: Type)) =>
              log(s"classOf $k referenced from $currentOwner")
              treeTypes += k
            case _                    =>
              log(s"${if (isAlias) "alias " else ""}$tp referenced from $currentOwner")
              treeTypes += tp
          }
          for (annot <- tp.annotations)
            descend(annot)
        }
        // e.g. val a = new Foo ; new a.Bar ; don't let a be reported as unused.
        t.tpe.prefix foreach {
          case SingleType(_, sym) => recordReference(sym)
          case _                  => ()
        }
      }

      if (t.symbol != null && t.symbol.exists)
        for (annot <- t.symbol.annotations)
          descend(annot)

      super.traverse(t)
    }
    def isSuppressed(sym: Symbol): Boolean = sym.hasAnnotation(UnusedClass)
    def isUnusedType(m: Symbol): Boolean = (
      m.isType
        && !isSuppressed(m)
        && !m.isTypeParameterOrSkolem // would be nice to improve this
        && (m.isPrivate || m.isLocalToBlock || isEffectivelyPrivate(m))
        && !treeTypes.exists(_.exists(_.typeSymbolDirect == m))
      )
    def isSyntheticWarnable(sym: Symbol) = {
      def privateSyntheticDefault: Boolean =
        cond(nme.defaultGetterToMethod(sym.name)) {
          case nme.CONSTRUCTOR => sym.owner.companion.isCaseClass
          case nme.copy        => sym.owner.typeSignature.member(nme.copy).isSynthetic
        }
      sym.isParameter || sym.isParamAccessor || sym.isDefaultGetter && !privateSyntheticDefault
    }
    def isUnusedTerm(m: Symbol): Boolean = (
      m.isTerm
        && !isSuppressed(m)
        && (!m.isSynthetic || isSyntheticWarnable(m))
        && ((m.isPrivate && !(m.isConstructor && m.owner.isAbstract)) || m.isLocalToBlock || isEffectivelyPrivate(m))
        && !targets(m)
        && !(m.name == nme.WILDCARD)              // e.g. val _ = foo
        && (m.isValueParameter || !ignoreNames(m.name.toTermName)) // serialization/repl methods
        && !treeTypes.exists(_ contains m)        // e.g. val a = new Foo ; new a.Bar
      )
    def isUnusedParam(m: Symbol): Boolean = (
      isUnusedTerm(m)
        && !m.isDeprecated
        && !m.owner.isDefaultGetter
        && !(m.isParamAccessor && (
        m.owner.isImplicit ||
          targets.exists(s => s.isParameter
            && s.name == m.name && s.owner.isConstructor && s.owner.owner == m.owner) // exclude ctor params
        ))
        && !(m.info.typeSymbol == UnitClass)
        && !(m.owner.isClass && m.owner.thisType.baseClasses.contains(AnnotationClass))
        && !ignore(m)
      )
    def unusedTypes = defnTrees.iterator.filter(t => isUnusedType(t.symbol))
    def unusedTerms = {
      // is this a getter-setter pair? and why is this a difficult question for traits?
      def sameReference(g: Symbol, s: Symbol) =
        if (g.accessed.exists && s.accessed.exists) g.accessed == s.accessed
        else g.owner == s.owner && g.setterName == s.name

      val all = defnTrees.iterator.filter(v => isUnusedTerm(v.symbol)).toSet
      // filter out setters if already warning for getter.
      val clean = all.filterNot(v => v.symbol.isSetter && all.exists(g => g.symbol.isGetter && sameReference(g.symbol, v.symbol)))
      clean.iterator
    }
    // local vars which are never set, except those already returned in unused
    def unsetVars = {
      def varsWithoutSetters = defnTrees.iterator.map(_.symbol).filter(t => t.isVar && !isExisting(t.setter))
      varsWithoutSetters.filter(v => !isSuppressed(v) && !setVars(v) && !isUnusedTerm(v))
    }
    def unusedParams = params.iterator.filter(isUnusedParam)
    def inDefinedAt(p: Symbol) = p.owner.isMethod && p.owner.name == nme.isDefinedAt && p.owner.owner.isAnonymousFunction
    def unusedPatVars = {
      // in elaboration of for comprehensions, patterns are duplicated;
      // track a patvar by its symbol position; "original" has a range pos
      val all = patvars.filterInPlace(_.symbol.pos.isDefined)
      val byPos = all.groupBy(_.symbol.pos.start)
      def isNotPrivateOrLocal(s: Symbol) = s.hasAccessorFlag && s.hasNoFlags(PRIVATE | LOCAL)
      def isUnusedPatVar(t: Tree): Boolean =
        byPos(t.symbol.pos.start).forall(p =>
             !targets(p.symbol)
          && !isNotPrivateOrLocal(p.symbol)
          && !ignore(p.symbol)
        )
      // the "original" tree has an opaque range;
      // for multi-var patdef, tree pos is transparent but sym pos is opaque;
      // use the field as the primary definition, and also remove it from targets
      // if it has a getter (in which case it has the "local" name to disambiguate).
      // Note that for uni-var patdef `val Some(x)`, tree pos is opaque.
      def isPrimaryPatVarDefinition(p: Tree): Boolean =
        p.symbol.pos.isOpaqueRange && {
          val primary = p.pos.isOpaqueRange || p.symbol.isPrivateLocal
          if (primary && nme.isLocalName(p.symbol.name))
            targets.subtractOne(p.symbol) // field is trivially accessed by its getter if it has one
          primary
        }
      all.iterator.filter(p =>
           isPrimaryPatVarDefinition(p)
        && isUnusedTerm(p.symbol)
        && isUnusedPatVar(p)
        && !nme.isFreshTermName(p.symbol.name)
        && !inDefinedAt(p.symbol)
      )
    }
  }

  class checkUnused(typer: Typer) {

    private def isMacroAnnotationExpansion(tree: Tree): Boolean = tree.hasSymbolField && isExpanded(tree.symbol)

    private def isMacroExpansion(tree: Tree): Boolean = hasMacroExpansionAttachment(tree) || isMacroAnnotationExpansion(tree)

    object skipMacroCall extends UnusedPrivates {
      override def qualifiesTerm(sym: Symbol): Boolean =
        super.qualifiesTerm(sym) && !sym.isMacro
    }
    object skipMacroExpansion extends UnusedPrivates {
      override def traverse(tree: Tree): Unit = if (!isMacroExpansion(tree)) super.traverse(tree)
    }
    object checkMacroExpandee extends UnusedPrivates {
      override def traverse(tree: Tree): Unit =
        if (!isMacroAnnotationExpansion(tree))
          super.traverse(if (hasMacroExpansionAttachment(tree)) macroExpandee(tree) else tree)
    }
    // collect definitions and refs from expandee (and normal trees) but only refs from expanded trees
    object checkMacroExpandeeAndExpandedRefs extends UnusedPrivates {
      object refCollector extends Traverser {
        override def traverse(tree: Tree): Unit = {
          tree match {
            case _: RefTree if isExisting(tree.symbol) => recordReference(tree.symbol)
            case _ =>
          }
          if (tree.tpe != null) tree.tpe.prefix.foreach {
            case SingleType(_, sym) => recordReference(sym)
            case _ =>
          }
          super.traverse(tree)
        }
      }
      override def traverse(tree: Tree): Unit =
        if (hasMacroExpansionAttachment(tree)) {
          super.traverse(macroExpandee(tree))
          refCollector.traverse(tree)
        }
        else if (isMacroAnnotationExpansion(tree))
          refCollector.traverse(tree)
        else super.traverse(tree)
    }

    private def warningsEnabled: Boolean = {
      val ss = settings
      import ss._
      warnUnusedPatVars || warnUnusedPrivates || warnUnusedLocals || warnUnusedParams
    }

    // `checkUnused` is invoked after type checking. we have to avoid using `typer.context.warning`, which uses
    // `context.owner` as the `site` of the warning, but that's the root symbol at this point.
    private val unusedWarnings = ListBuffer.empty[(Position, String, WarningCategory, Symbol)]
    private def emitUnusedWarning(pos: Position, msg: String, category: WarningCategory, site: Symbol): Unit =
      unusedWarnings.addOne((pos, msg, category, site))
    private def reportAll(): Unit = {
      implicit val ordering = new Ordering[Position] {
        def posOf(p: Position): Int = if (p.isDefined) p.point else -1
        override def compare(x: Position, y: Position): Int = posOf(x) - posOf(y)
      }
      unusedWarnings.toArray.sortBy(_._1).foreach { case (pos, msg, category, site) => runReporting.warning(pos, msg, category, site) }
      unusedWarnings.clear()
    }

    def run(unusedPrivates: UnusedPrivates)(body: Tree): Unit = {
      unusedPrivates.traverse(body)

      if (settings.warnUnusedLocals || settings.warnUnusedPrivates) {
        def shouldWarnOn(sym: Symbol) =
          if (sym.isPrivate) settings.warnUnusedPrivates && !sym.isTopLevel
          else settings.warnUnusedLocals
        val valAdvice = "is never updated: consider using immutable val"
        def varAdvice(v: Symbol) = if (v.accessedOrSelf.hasAttachment[MultiDefAttachment.type]) "is never updated: consider refactoring vars to a separate definition" else valAdvice
        def wcat(sym: Symbol) = if (sym.isPrivate) WarningCategory.UnusedPrivates else WarningCategory.UnusedLocals
        def termWarning(defn: SymTree): Unit = {
          val sym = defn.symbol
          val pos =
            sym match {
              case sym if sym.pos.isDefined                        => sym.pos
              case sym: TermSymbol if sym.referenced.pos.isDefined => sym.referenced.pos
              case _ if defn.pos.isDefined                         => defn.pos
              case _                                               => NoPosition
            }
          val why = if (sym.isPrivate) "private" else "local"
          var cond = "is never used"
          def long = if (settings.uniqid.value) s" (${sym.nameString})" else ""
          def getterNameString(sym: Symbol): String = sym.getterName.decoded + long
          val what =
            if (sym.isDefaultGetter) "default argument"
            else if (sym.isConstructor) "constructor"
            else if (sym.isSetter) { cond = varAdvice(sym); s"var ${getterNameString(sym)}" }
            else if (sym.isVar || sym.isGetter && sym.accessed.isVar) s"var ${sym.nameString}"
            else if (sym.isVal || sym.isGetter && sym.accessed.isVal || sym.isLazy) s"val ${sym.nameString}"
            else if (sym.isMethod) s"method ${sym.nameString}"
            else if (sym.isModule) s"object ${sym.nameString}"
            else "term"
          // consider using sym.owner.fullLocationString
          emitUnusedWarning(pos, s"$why $what in ${sym.owner} $cond", wcat(sym), sym)
        }
        def typeWarning(defn: SymTree): Unit = {
          val sym = defn.symbol
          val why = if (sym.isPrivate) "private" else "local"
          val pos = if (sym.pos.isDefined) sym.pos else defn.pos
          emitUnusedWarning(pos, s"$why ${sym.fullLocationString} is never used", wcat(sym), sym)
        }

        for (defn <- unusedPrivates.unusedTerms if shouldWarnOn(defn.symbol)) { termWarning(defn) }
        for (defn <- unusedPrivates.unusedTypes if shouldWarnOn(defn.symbol)) { typeWarning(defn) }

        for (v <- unusedPrivates.unsetVars) {
          emitUnusedWarning(v.pos, s"local var ${v.nameString} in ${v.owner} ${varAdvice(v)}", WarningCategory.UnusedPrivates, v)
        }
      }
      if (settings.warnUnusedPatVars)
        for (v <- unusedPrivates.unusedPatVars)
          emitUnusedWarning(v.symbol.pos, s"pattern var ${v.symbol.name.dropLocal} in ${v.symbol.owner} is never used", WarningCategory.UnusedPatVars, v.symbol)
      if (settings.warnUnusedParams) {
        // don't warn unused args of overriding methods (or methods matching in self-type)
        def isImplementation(m: Symbol): Boolean = m.isMethod && {
          def classOf(s: Symbol): Symbol = if (s.isClass || s == NoSymbol) s else classOf(s.owner)
          val classOfM = classOf(m)
          if (classOfM.hasSelfType) {
            val opc = new overridingPairs.PairsCursor(classOfM) {
              override protected def bases: List[Symbol] = self.baseClasses
            }
            opc.iterator.exists(pair => pair.low == m || pair.high == m)
          } else {
            val opc = new overridingPairs.PairsCursor(classOfM)
            opc.iterator.exists(_.low == m)
          }
        }
        def isEmptyMarker(p: Symbol): Boolean = p.info.members.reverseIterator.forall(isUniversalMember(_)) // nonTrivialMembers(p).isEmpty
        def isConvention(p: Symbol): Boolean = (
            p.name.decoded == "args" && p.owner.isMethod && p.owner.name.decoded == "main"
          ||
            p.isImplicit && cond(p.tpe.typeSymbol) { case SameTypeClass | SubTypeClass | DummyImplicitClass => true }
        )
        def warningIsOnFor(s: Symbol) =
          if (!s.isImplicit) settings.warnUnusedExplicits
          else {
            if (!s.isSynthetic) settings.warnUnusedImplicits
            else settings.warnUnusedSynthetics
          } && !isEmptyMarker(s)
        def warnable(s: Symbol) = (
          warningIsOnFor(s)
            && !isImplementation(s.owner)
            && !isConvention(s)
          )
        for (s <- unusedPrivates.unusedParams if warnable(s)) {
          val what =
            if (s.name.startsWith(nme.EVIDENCE_PARAM_PREFIX)) s"evidence parameter ${s.nameString} of type ${s.tpe}"
            else s"parameter ${s.nameString}"
          val where =
            if (s.owner.isAnonymousFunction) "anonymous function" else s.owner.toString
          emitUnusedWarning(s.pos, s"$what in $where is never used", WarningCategory.UnusedParams, s)
        }
      }
    }
    def apply(unit: CompilationUnit): Unit = if (warningsEnabled && !unit.isJava && !typer.context.reporter.hasErrors) {
      val body = unit.body
      // TODO the message should distinguish whether the non-usage is before or after macro expansion.
      settings.warnMacros.value match {
        case "default"=> run(checkMacroExpandeeAndExpandedRefs)(body)
        case "none"   => run(skipMacroExpansion)(body)
        case "before" => run(checkMacroExpandee)(body)
        case "after"  => run(skipMacroCall)(body)
        case "both"   => run(checkMacroExpandee)(body) ; run(skipMacroCall)(body)
      }
      reportAll()
    }
  }


  trait TyperDiagnostics {
    _: Typer =>

    def permanentlyHiddenWarning(pos: Position, hidden: Name, defn: Symbol) =
      context.warning(pos, "imported `%s` is permanently hidden by definition of %s".format(hidden, defn.fullLocationString), WarningCategory.OtherShadowing)

    /** Returns Some(msg) if the given tree is untyped apparently due
     *  to a cyclic reference, and None otherwise.
     */
    def cyclicReferenceMessage(sym: Symbol, tree: Tree, trace: Array[Symbol], pos: Position) = {
      def symWasOverloaded(sym: Symbol) = sym.owner.isClass && sym.owner.info.member(sym.name).isOverloaded
      def cyclicAdjective(sym: Symbol)  = if (symWasOverloaded(sym)) "overloaded" else "recursive"

      val badsym = if (!sym.isSynthetic) sym else {
        val organics = trace.filter(!_.isSynthetic)
        if (organics.length == 0) sym
        else if (organics.length == 1) organics(0)
        else organics.find(_.pos.focus == pos.focus).getOrElse(organics(0))
      }
      def help = if (!badsym.isSynthetic || settings.cyclic.value) "" else
        s"; $badsym is synthetic; use -Vcyclic to find which definition needs an explicit type"
      condOpt(tree) {
        case ValDef(_, _, TypeTree(), _)       => s"recursive $badsym needs type$help"
        case DefDef(_, _, _, _, TypeTree(), _) => s"${cyclicAdjective(badsym)} $badsym needs result type$help"
        case Import(_, _)                      =>
          sm"""encountered unrecoverable cycle resolving import.
              |Note: this is often due in part to a class depending on a definition nested within its companion.
              |If applicable, you may wish to try moving some members into another object."""
      }
    }

    // warn about class/method/type-members' type parameters that shadow types already in scope
    def warnTypeParameterShadow(tparams: List[TypeDef], sym: Symbol): Unit =
      if (settings.warnTypeParameterShadow && !isPastTyper && !sym.isSynthetic) {
        def enclClassOrMethodOrTypeMember(c: Context): Context =
          if (!c.owner.exists || c.owner.isClass || c.owner.isMethod || (c.owner.isType && !c.owner.isParameter)) c
          else enclClassOrMethodOrTypeMember(c.outer)

        tparams.filter(_.name != typeNames.WILDCARD).foreach { tp =>
        // we don't care about type params shadowing other type params in the same declaration
        enclClassOrMethodOrTypeMember(context).outer.lookupSymbol(tp.name, s => s != tp.symbol && s.hasRawInfo && reallyExists(s)) match {
          case LookupSucceeded(_, sym2) => context.warning(tp.pos,
            s"type parameter ${tp.name} defined in $sym shadows $sym2 defined in ${sym2.owner}. You may want to rename your type parameter, or possibly remove it.",
            WarningCategory.LintTypeParameterShadow)
          case _ =>
        }
      }
    }

    /** Report a type error.
     *
     *  @param pos    The position where to report the error
     *  @param ex     The exception that caused the error
     */
    def reportTypeError(context0: Context, pos: Position, ex: TypeError): Unit = {
      if (ex.pos == NoPosition) ex.pos = pos
      // TODO: should be replaced by throwErrors
      // but it seems that throwErrors excludes some of the errors that should actually be
      // buffered, causing TypeErrors to fly around again. This needs some more investigation.
      if (!context0.reportErrors) throw ex
      if (settings.isDebug) ex.printStackTrace()

      ex match {
        // see comments to TypeSigError for an explanation of this special case
        case _: CyclicReference if context0.owner.isTermMacro => throw ex
        case CyclicReference(sym, info: TypeCompleter, trace) =>
          val pos = info.tree match {
            case Import(expr, _)  => expr.pos
            case _                => ex.pos
          }
          context0.error(pos, cyclicReferenceMessage(sym, info.tree, trace, pos).getOrElse(ex.getMessage))

          if (sym == ObjectClass) throw new FatalError(s"cannot redefine root $sym")
        case _ =>
          context0.error(ex.pos, ex.msg)
      }
    }

    /** Check that type `tree` does not refer to private
     *  components unless itself is wrapped in something private
     *  (`owner` tells where the type occurs).
     */
    def checkNoEscapingPrivates(typer: Typer, owner: Symbol, tree: Tree): Tree =
      if (owner.isJavaDefined) tree
      else new CheckNoEscaping(typer, owner, tree).check(tree)

    /** Check that type of given tree does not contain local or private components. */
    private final class CheckNoEscaping(typer: Typer, owner: Symbol, tree: Tree) extends TypeMap {
      private var hiddenSymbols: List[Symbol] = Nil

      def check(tree: Tree): Tree = {
        import typer.TyperErrorGen._
        val tp1 = apply(tree.tpe)
        if (hiddenSymbols.isEmpty) tree setType tp1
        else if (hiddenSymbols exists (_.isErroneous)) HiddenSymbolWithError(tree)
        else if (tp1.typeSymbol.isAnonymousClass)
          check(tree setType tp1.typeSymbol.classBound)
        else if (owner == NoSymbol)
          tree setType packSymbols(hiddenSymbols.reverse, tp1)
        else if (!isPastTyper) { // privates
          val badSymbol = hiddenSymbols.head
          SymbolEscapesScopeError(tree, badSymbol)
        } else tree
      }

      def addHidden(sym: Symbol) =
        if (!(hiddenSymbols contains sym)) hiddenSymbols = sym :: hiddenSymbols

      override def apply(t: Type): Type = {
        def checkNoEscape(sym: Symbol): Unit = {
          if (sym.isPrivate && !sym.hasFlag(SYNTHETIC_PRIVATE)) {
            var o = owner
            while (o != NoSymbol && o != sym.owner && o != sym.owner.linkedClassOfClass &&
              !o.isLocalToBlock && !o.isPrivate &&
              !o.privateWithin.hasTransOwner(sym.owner))
              o = o.owner
            if (o == sym.owner || o == sym.owner.linkedClassOfClass)
              addHidden(sym)
          }
        }
        mapOver(
          t match {
            case TypeRef(_, sym, args) =>
              checkNoEscape(sym)
              if (!hiddenSymbols.isEmpty && hiddenSymbols.head == sym &&
                sym.isAliasType && sameLength(sym.typeParams, args)) {
                hiddenSymbols = hiddenSymbols.tail
                t.dealias
              } else t
            case SingleType(_, sym) =>
              checkNoEscape(sym)
              t
            case _ =>
              t
          })
      }
    }
  }
}
