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

package scala.tools.nsc.transform.patmat

import scala.annotation._
import scala.collection.mutable
import scala.tools.nsc.symtab.Flags.{MUTABLE, STABLE}
import scala.tools.nsc.Reporting.WarningCategory

/** Optimize and analyze matches based on their TreeMaker-representation.
 *
 * The patmat translation doesn't rely on this, so it could be disabled in principle.
 *  - well, not quite: the backend crashes if we emit duplicates in switches (e.g. scala/bug#7290)
 */
trait MatchOptimization extends MatchTreeMaking with MatchApproximation {
  import global._
  import global.definitions._

  ////
  trait CommonSubconditionElimination extends OptimizedCodegen with MatchApproximator {
    /** a flow-sensitive, generalised, common sub-expression elimination
     * reuse knowledge from performed tests
     * the only sub-expressions we consider are the conditions and results of the three tests (type, type&equality, equality)
     * when a sub-expression is shared, it is stored in a mutable variable
     * the variable is floated up so that its scope includes all of the program that shares it
     * we generalize sharing to implication, where b reuses a if a => b and priors(a) => priors(b) (the priors of a sub expression form the path through the decision tree)
     */
    def doCSE(prevBinder: Symbol, cases: List[List[TreeMaker]], @unused pt: Type, selectorPos: Position): List[List[TreeMaker]] = {
      debug.patmat("before CSE:")
      showTreeMakers(cases)

      val testss = approximateMatchConservative(prevBinder, cases)

      // interpret:
      val dependencies    = new mutable.LinkedHashMap[Test, mutable.LinkedHashSet[Prop]]
      val tested          = new mutable.LinkedHashSet[Prop]
      val reusesMap       = new mutable.LinkedHashMap[Int, Test]
      val reusesTest      = { (test: Test) => reusesMap.get(test.id) }
      val registerReuseBy = { (priorTest: Test, later: Test) =>
        assert(!reusesMap.contains(later.id), reusesMap(later.id))
        reusesMap(later.id) = priorTest
      }

      // TODO: use SAT solver instead of hashconsing props and approximating implication by subset/equality
      def storeDependencies(test: Test) = {
        val cond = test.prop

        def simplify(c: Prop): Set[Prop] = c match {
          case And(ops)                   => ops.flatMap(simplify).toSet
          case Or(ops)                    => Set(False) // TODO: make more precise
          case Not(Eq(Var(_), NullConst)) => Set.empty  // not worth remembering
          case True                       => Set.empty  // same
          case _                          => Set(c)
        }
        val conds = simplify(cond)

        if (conds(False)) false // stop when we encounter a definite "no" or a "not sure"
        else {
          if (!conds.isEmpty) {
            tested ++= conds

            // is there an earlier test that checks our condition and whose dependencies are implied by ours?
            dependencies find {
              case (priorTest, deps) =>
                ((simplify(priorTest.prop) == conds) || // our conditions are implied by priorTest if it checks the same thing directly
                    (conds subsetOf deps)               // or if it depends on a superset of our conditions
                ) && (deps subsetOf tested)             // the conditions we've tested when we are here in the match satisfy the prior test, and hence what it tested
            } foreach {
              case (priorTest, _) =>
                // if so, note the dependency in both tests
                registerReuseBy(priorTest, test)
            }

            dependencies(test) = tested.clone()
          }
          true
        }
      }


      testss foreach { tests =>
        tested.clear()
        tests dropWhile storeDependencies
      }
      debug.patmat("dependencies: "+ dependencies)

      // find longest prefix of tests that reuse a prior test, and whose dependent conditions monotonically increase
      // then, collapse these contiguous sequences of reusing tests
      // store the result of the final test and the intermediate results in hoisted mutable variables (TODO: optimize: don't store intermediate results that aren't used)
      // replace each reference to a variable originally bound by a collapsed test by a reference to the hoisted variable
      val reused = new mutable.HashMap[TreeMaker, ReusedCondTreeMaker]
      val reusedOrOrig = (tm: TreeMaker) => reused.getOrElse(tm, tm)

      // maybe collapse: replace shared prefix of tree makers by a ReusingCondTreeMaker
      // once this has been computed, we'll know which tree makers are reused,
      // and we'll replace those by the ReusedCondTreeMakers we've constructed (and stored in the reused map)
      val collapsed = testss map { tests =>
        // map tests to the equivalent list of treemakers, replacing shared prefixes by a reusing treemaker
        // if there's no sharing, simply map to the tree makers corresponding to the tests
        var currDeps = mutable.LinkedHashSet.empty[Prop]
        val (sharedPrefix, suffix) = tests span { test =>
          (test.prop == True) || (for(
              reusedTest <- reusesTest(test);
              nextDeps <- dependencies.get(reusedTest);
              diff <- (nextDeps diff currDeps).headOption;
              _ <- Some({ currDeps = nextDeps }))
                yield diff).nonEmpty
        }

        val collapsedTreeMakers =
          if (sharedPrefix.isEmpty) None
          else { // even sharing prefixes of length 1 brings some benefit (overhead-percentage for compiler: 26->24%, lib: 19->16%)
            for (test <- sharedPrefix; reusedTest <- reusesTest(test)) reusedTest.treeMaker match {
              case reusedCTM: CondTreeMaker => reused(reusedCTM) = ReusedCondTreeMaker(reusedCTM, selectorPos)
              case _ =>
            }

            debug.patmat(s"sharedPrefix: $sharedPrefix")
            debug.patmat(s"suffix: $suffix")
            // if the shared prefix contains interesting conditions (!= True)
            // and the last of such interesting shared conditions reuses another treemaker's test
            // replace the whole sharedPrefix by a ReusingCondTreeMaker
            for (lastShared <- sharedPrefix.reverse.dropWhile(_.prop == True).headOption; _ <- reusesTest(lastShared))
            yield ReusingCondTreeMaker(sharedPrefix, reusesTest, reusedOrOrig) :: suffix.map(_.treeMaker)
          }

        collapsedTreeMakers getOrElse tests.map(_.treeMaker) // sharedPrefix need not be empty (but it only contains True-tests, which are dropped above)
      }

      // replace original treemakers that are reused (as determined when computing collapsed),
      // by ReusedCondTreeMakers
      val reusedMakers = collapsed mapConserve (_ mapConserve reusedOrOrig)
      debug.patmat("after CSE:")
      showTreeMakers(reusedMakers)
      reusedMakers
    }

    object ReusedCondTreeMaker {
      def apply(orig: CondTreeMaker, selectorPos: Position) = new ReusedCondTreeMaker(orig.prevBinder, orig.nextBinder, orig.cond, orig.res, selectorPos, orig.pos)
    }
    class ReusedCondTreeMaker(prevBinder: Symbol, val nextBinder: Symbol, cond: Tree, res: Tree, selectorPos: Position, val pos: Position) extends TreeMaker {
      lazy val localSubstitution        = Substitution(List(prevBinder), List(CODE.REF(nextBinder)))
      lazy val storedCond               = freshSym(selectorPos, BooleanTpe, "rc") setFlag MUTABLE
      lazy val treesToHoist: List[Tree] = {
        nextBinder setFlag MUTABLE | STABLE // mark stable to tell lambalift not to capture these as the value will be assigned prior to capture and never reassigned.
        nextBinder.setPos(selectorPos)
        List(storedCond, nextBinder) map (b => ValDef(b, codegen.mkZero(b.info)))
      }

      // TODO: finer-grained duplication
      def chainBefore(next: Tree)(casegen: Casegen): Tree =
        atPos(pos)(casegen.flatMapCondStored(cond, storedCond, res, nextBinder, substitution(next).duplicate))

      override def toString = "Memo"+((nextBinder.name, storedCond.name, cond, res, substitution))
    }

    case class ReusingCondTreeMaker(sharedPrefix: List[Test], reusesTest: Test => Option[Test], toReused: TreeMaker => TreeMaker) extends TreeMaker { import CODE._
      val pos = sharedPrefix.last.treeMaker.pos

      lazy val localSubstitution = {
        // replace binder of each dropped treemaker by corresponding binder bound by the most recent reused treemaker
        var mostRecentReusedMaker: ReusedCondTreeMaker = null
        def mapToStored(droppedBinder: Symbol) = if (mostRecentReusedMaker eq null) Nil else List((droppedBinder, REF(mostRecentReusedMaker.nextBinder)))
        val (from, to) = sharedPrefix.flatMap { dropped =>
          reusesTest(dropped).map(test => toReused(test.treeMaker)).foreach {
            case reusedMaker: ReusedCondTreeMaker =>
              mostRecentReusedMaker = reusedMaker
            case _ =>
          }

          // TODO: have super-trait for retrieving the variable that's operated on by a tree maker
          // and thus assumed in scope, either because it binds it or because it refers to it
          dropped.treeMaker match {
            case dropped: FunTreeMaker =>
              mapToStored(dropped.nextBinder)
            case _ => Nil
          }
        }.unzip
        val rerouteToReusedBinders = Substitution(from, to)

        val collapsedDroppedSubst = sharedPrefix map (t => (toReused(t.treeMaker).substitution))

        collapsedDroppedSubst.foldLeft(rerouteToReusedBinders)(_ >> _)
      }

      lazy val lastReusedTreeMaker = sharedPrefix.reverse.flatMap(tm => reusesTest(tm) map (test => toReused(test.treeMaker))).collectFirst{case x: ReusedCondTreeMaker => x}.head

      def chainBefore(next: Tree)(casegen: Casegen): Tree = {
        // TODO: finer-grained duplication -- MUST duplicate though, or we'll get VerifyErrors since sharing trees confuses lambdalift,
        // and in its confusion it emits illegal casts (diagnosed by Grzegorz: checkcast T ; invokevirtual S.m, where T not a subtype of S)
        atPos(pos)(casegen.ifThenElseZero(REF(lastReusedTreeMaker.storedCond), substitution(next).duplicate))
      }
      override def toString = "R"+((lastReusedTreeMaker.storedCond.name, substitution))
    }
  }

  //// SWITCHES
  trait SwitchEmission extends TreeMakers with MatchMonadInterface {
    import treeInfo.isGuardedCase

    def inForceDesugar: Boolean

    abstract class SwitchMaker {
      abstract class SwitchableTreeMakerExtractor { def unapply(x: TreeMaker): Option[Tree] }
      val SwitchableTreeMaker: SwitchableTreeMakerExtractor

      def alternativesSupported: Boolean

      // when collapsing guarded switch cases we may sometimes need to jump to the default case
      // however, that's not supported in exception handlers, so when we can't jump when we need it, don't emit a switch
      // TODO: make more fine-grained, as we don't always need to jump
      def canJump: Boolean

      /** Should exhaustivity analysis be skipped? */
      def unchecked: Boolean


      def isDefault(x: CaseDef): Boolean
      def defaultSym: Symbol
      def defaultBody: Tree
      def defaultCase(scrutSym: Symbol = defaultSym, guard: Tree = EmptyTree, body: Tree = defaultBody): CaseDef

      object GuardAndBodyTreeMakers {
          def unapply(tms: List[TreeMaker]): Option[(Tree, Tree)] = {
            tms match {
              case (btm@BodyTreeMaker(body, _)) :: Nil => Some((EmptyTree, btm.substitution(body)))
              case (gtm@GuardTreeMaker(guard)) :: (btm@BodyTreeMaker(body, _)) :: Nil => Some((gtm.substitution(guard), btm.substitution(body)))
              case _ => None
            }
          }
      }

      private val defaultLabel: Symbol =  newSynthCaseLabel("default")

      /** Collapse guarded cases that switch on the same constant (the last case may be unguarded).
       *
       * Cases with patterns A and B switch on the same constant iff for all values x that match A also match B and vice versa.
       * (This roughly corresponds to equality on trees modulo alpha renaming and reordering of alternatives.)
       *
       * The rewrite only applies if some of the cases are guarded (this must be checked before invoking this method).
       *
       * The rewrite goes through the switch top-down and merges each case with the subsequent cases it is implied by
       * (i.e. it matches if they match, not taking guards into account)
       *
       * If there are no unreachable cases, all cases can be uniquely assigned to a partition of such 'overlapping' cases,
       * save for the default case (thus we jump to it rather than copying it several times).
       * (The cases in a partition are implied by the principal element of the partition.)
       *
       * The overlapping cases are merged into one case with their guards pushed into the body as follows
       * (with P the principal element of the overlapping patterns Pi):
       *
       *    `{case Pi if(G_i) => B_i }*` is rewritten to `case P => {if(G_i) B_i}*`
       *
       * The rewrite fails (and returns Nil) when:
       *   (1) there is a subsequence of overlapping cases that has an unguarded case in the middle;
       *       only the last case of each subsequence of overlapping cases may be unguarded (this is implied by unreachability)
       *
       *   (2) there are overlapping cases that differ (tested by `caseImpliedBy`)
       *       cases with patterns A and B are overlapping if for SOME value x, A matches x implies B matches y OR vice versa  <-- note the difference with case equality defined above
       *       for example `case 'a' | 'b' =>` and `case 'b' =>` are different and overlapping (overlapping and equality disregard guards)
       *
       * The second component of the returned tuple indicates whether we'll need to emit a labeldef to jump to the default case.
       */
      private def collapseGuardedCases(cases: List[CaseDef]): (List[CaseDef], Boolean) = {
        // requires(same.forall(caseEquals(same.head)))
        // requires(same.nonEmpty, same)
        def collapse(same: List[CaseDef], isDefault: Boolean): CaseDef = {
          val commonPattern = same.head.pat
          // jump to default case (either the user-supplied one or the synthetic one)
          // unless we're collapsing the default case: then we re-use the same body as the synthetic catchall (throwing a matcherror, rethrowing the exception)
          val jumpToDefault: Tree =
            if (isDefault || !canJump) defaultBody
            else Apply(Ident(defaultLabel), Nil)

          val guardedBody = same.foldRight(jumpToDefault) {
            // the last case may be unguarded (we know it's the last one since fold's accum == jumpToDefault)
            // --> replace jumpToDefault by the unguarded case's body
            case (CaseDef(_, EmptyTree, b), `jumpToDefault`)       => b
            case (cd @ CaseDef(_, g, b), els) if isGuardedCase(cd) => If(g, b, els)
            case x                                                 => throw new MatchError(x)
          }

          // if the cases that we're going to collapse bind variables,
          // must replace them by the single binder introduced by the collapsed case
          val binders = same.collect { case CaseDef(x @ Bind(_, _), _, _) if x.symbol != NoSymbol => x.symbol }
          val (pat, guardedBodySubst) =
            if (binders.isEmpty) (commonPattern, guardedBody)
            else {
              // create a single fresh binder to subsume the old binders (and their types)
              // TODO: I don't think the binder's types can actually be different (due to checks in caseEquals)
              // if they do somehow manage to diverge, the lub might not be precise enough and we could get a type error
              // TODO: reuse name exactly if there's only one binder in binders
              val binder = freshSym(binders.head.pos, lub(binders.map(_.tpe)), binders.head.name.toString)

              // the patterns in same are equal (according to caseEquals)
              // we can thus safely pick the first one arbitrarily, provided we correct binding
              val origPatWithoutBind = commonPattern match {
                case Bind(_, orig) => orig
                case o => o
              }
              // need to replace `defaultSym` as well -- it's used in `defaultBody` (see `jumpToDefault` above)
              val unifiedBody = guardedBody.substituteSymbols(defaultSym :: binders, binder :: binders.map(_ => binder))
              (Bind(binder, origPatWithoutBind), unifiedBody)
            }

          val samePos = wrappingPos(same.flatMap(k => List(k.pat, k.body)))
          atPos(samePos)(CaseDef(pat, EmptyTree, guardedBodySubst))
        }

        // requires cases.exists(isGuardedCase) (otherwise the rewrite is pointless)
        var remainingCases = cases
        val collapsed      = scala.collection.mutable.ListBuffer.empty[CaseDef]

        // when some of collapsed cases (except for the default case itself) did not include an unguarded case
        // we'll need to emit a labeldef for the default case
        var needDefault  = false

        while (remainingCases.nonEmpty) {
          val currCase              = remainingCases.head
          val currIsDefault         = isDefault(CaseDef(currCase.pat, EmptyTree, EmptyTree))
          val (impliesCurr, others) =
            // the default case is implied by all cases, no need to partition (and remainingCases better all be default cases as well)
            if (currIsDefault) (remainingCases.tail, Nil)
            else remainingCases.tail partition (caseImplies(currCase))

          val unguardedComesLastOrAbsent =
            (!isGuardedCase(currCase) && impliesCurr.isEmpty) || { val LastImpliesCurr = impliesCurr.length - 1
            impliesCurr.indexWhere(oc => !isGuardedCase(oc)) match {
              // if all cases are guarded we will have to jump to the default case in the final else
              // (except if we're collapsing the default case itself)
              case -1 =>
                if (!currIsDefault) needDefault = true
                true

              // last case is not guarded, no need to jump to the default here
              // note: must come after case -1 => (since LastImpliesCurr may be -1)
              case LastImpliesCurr => true

              case _ => false
            }}

          if (unguardedComesLastOrAbsent /*(1)*/ && impliesCurr.forall(caseEquals(currCase)) /*(2)*/) {
            collapsed += (
              if (impliesCurr.isEmpty && !isGuardedCase(currCase)) currCase
              else collapse(currCase :: impliesCurr, currIsDefault)
            )

            remainingCases = others
          } else { // fail
            collapsed.clear()
            remainingCases = Nil
          }
        }

        (collapsed.toList, needDefault)
      }

      private def caseEquals(x: CaseDef)(y: CaseDef) = patternEquals(x.pat)(y.pat)
      private def patternEquals(x: Tree)(y: Tree): Boolean = (x, y) match {
        case (Alternative(xs), Alternative(ys)) =>
          xs.forall(x => ys.exists(patternEquals(x))) &&
          ys.forall(y => xs.exists(patternEquals(y)))
        case (Alternative(pats), _) => pats.forall(p => patternEquals(p)(y))
        case (_, Alternative(pats)) => pats.forall(q => patternEquals(x)(q))
        // regular switch
        case (Literal(Constant(cx)), Literal(Constant(cy))) => cx == cy
        case (Ident(nme.WILDCARD), Ident(nme.WILDCARD))     => true
        // type-switch for catch
        case (Bind(_, Typed(Ident(nme.WILDCARD), tpX)), Bind(_, Typed(Ident(nme.WILDCARD), tpY))) => tpX.tpe =:= tpY.tpe
        case _ => false
      }

      // if y matches then x matches for sure (thus, if x comes before y, y is unreachable)
      private def caseImplies(x: CaseDef)(y: CaseDef) = patternImplies(x.pat)(y.pat)
      private def patternImplies(x: Tree)(y: Tree): Boolean = (x, y) match {
        // since alternatives are flattened, must treat them as separate cases
        case (Alternative(pats), _) => pats.exists(p => patternImplies(p)(y))
        case (_, Alternative(pats)) => pats.exists(q => patternImplies(x)(q))
        // regular switch
        case (Literal(Constant(cx)), Literal(Constant(cy))) => cx == cy
        case (Ident(nme.WILDCARD), _)                       => true
        // type-switch for catch
        case (Typed(Ident(nme.WILDCARD), tpX), Typed(Ident(nme.WILDCARD), tpY)) => instanceOfTpImplies(tpY.tpe, tpX.tpe)
        // peel off binders -- they don't influence matching
        case (Bind(_, x), Bind(_, y))                                           => patternImplies(x)(y)
        case (Bind(_, x), y)                                                    => patternImplies(x)(y)
        case (x, Bind(_, y))                                                    => patternImplies(x)(y)
        case _                                                                  => false
      }

      private def noGuards(cs: List[CaseDef]): Boolean = !cs.exists(isGuardedCase)

      // must do this before removing guards from cases and collapsing (scala/bug#6011, scala/bug#6048)
      def unreachableCase(cases: List[CaseDef]): Option[CaseDef] = {
        @tailrec
        def loop(cases: List[CaseDef]): Option[CaseDef] = cases match {
          case head :: next :: _ if isDefault(head)                                    => Some(next) // subsumed by the next case, but faster
          case head :: rest if !isGuardedCase(head) || head.guard.tpe =:= ConstantTrue => rest find caseImplies(head) match { case s @ Some(_) => s case None => loop(rest) }
          case head :: _ if head.guard.tpe =:= ConstantFalse                           => Some(head)
          case _ :: rest                                                               => loop(rest)
          case _                                                                       => None
        }
        loop(cases)
      }

      // empty list ==> failure
      def apply(cases: List[(Symbol, List[TreeMaker])], pt: Type): List[CaseDef] =
        // generate if-then-else for 1 case switch (avoids verify error... can't imagine a one-case switch being faster than if-then-else anyway)
        if (cases.isEmpty || cases.tail.isEmpty) Nil
        else {
          val caseDefs = traverseOpt(cases) { case (scrutSym, makers) =>
            makers match {
              // default case
              case GuardAndBodyTreeMakers(guard, body) =>
                Some(defaultCase(scrutSym, guard, body))
              // constant (or typetest for typeSwitch)
              case SwitchableTreeMaker(pattern) :: GuardAndBodyTreeMakers(guard, body) =>
                Some(CaseDef(pattern, guard, body))
              // alternatives
              case AlternativesTreeMaker(_, altss, pos) :: GuardAndBodyTreeMakers(guard, body) if alternativesSupported =>
                // succeed iff they were all switchable
                val switchableAlts = traverseOpt(altss) {
                  case SwitchableTreeMaker(pattern) :: Nil =>
                    Some(pattern)
                  case _ =>
                    None
                }

                switchableAlts map { switchableAlts =>
                  def extractConst(t: Tree) = t match {
                    case Literal(const) => const
                    case _              => t
                  }
                  // scala/bug#7290 Discard duplicate alternatives that would crash the backend
                  val distinctAlts = distinctBy(switchableAlts)(extractConst)
                  if (distinctAlts.size < switchableAlts.size) {
                    val duplicated = switchableAlts.groupBy(extractConst).flatMap(_._2.drop(1).take(1)) // report the first duplicated
                    typer.context.warning(pos,
                      s"Pattern contains duplicate alternatives: ${duplicated.mkString(", ")}",
                      WarningCategory.OtherMatchAnalysis)
                  }
                  CaseDef(Alternative(distinctAlts), guard, body)
                }
              case _ =>
                // debug.patmat("can't emit switch for "+ makers)
                None //failure (can't translate pattern to a switch)
            }
          }

          val caseDefsWithGuards = caseDefs match {
            case None      => return Nil
            case Some(cds) => cds
          }

          // a switch with duplicate cases yields a verify error,
          // and a switch with duplicate cases and guards cannot soundly be rewritten to an unguarded switch
          // (even though the verify error would disappear, the behaviour would change)
          val allReachable = unreachableCase(caseDefsWithGuards).map(cd => reportUnreachable(cd.body.pos)).isEmpty

          if (!allReachable) Nil
          else if (noGuards(caseDefsWithGuards)) {
            if (isDefault(caseDefsWithGuards.last)) caseDefsWithGuards
            else caseDefsWithGuards :+ defaultCase()
          } else {
            // collapse identical cases with different guards, push guards into body for all guarded cases
            // this translation is only sound if there are no unreachable (duplicate) cases
            // it should only be run if there are guarded cases, and on failure it returns Nil
            val (collapsed, needDefaultLabel) = collapseGuardedCases(caseDefsWithGuards)

            if (collapsed.isEmpty || (needDefaultLabel && !canJump)) Nil
            else {
              def wrapInDefaultLabelDef(cd: CaseDef): CaseDef =
                if (needDefaultLabel) deriveCaseDef(cd){ b =>
                  // If `b` is synthesized in SwitchMaker (by `collapseGuardedCases` or by `defaultCase`)
                  // it is not yet typed. In order to assign the correct type to the label, type the case body.
                  // See scala/scala#10926, pos/t13060.scala.
                  val b1 = if (b.tpe == null) typer.typed(b, pt) else b
                  defaultLabel setInfo MethodType(Nil, b1.tpe.deconst)
                  LabelDef(defaultLabel, Nil, b1)
                } else cd

              val last = collapsed.last
              if (isDefault(last)) {
                if (!needDefaultLabel) collapsed
                else collapsed.init :+ wrapInDefaultLabelDef(last)
              } else collapsed :+ wrapInDefaultLabelDef(defaultCase())
            }
          }
        }
    }

    class RegularSwitchMaker(scrutSym: Symbol, matchFailGenOverride: Option[Tree => Tree], val unchecked: Boolean) extends SwitchMaker { import CODE._
      val switchableTpe = Set(ByteTpe, ShortTpe, IntTpe, CharTpe, StringTpe)
      val alternativesSupported = true
      val canJump = !inForceDesugar

      // Constant folding sets the type of a constant tree to `ConstantType(Constant(folded))`
      // The tree itself can be a literal, an ident, a selection, ...
      object SwitchablePattern { def unapply(pat: Tree): Option[Tree] = pat.tpe match {
        case const: ConstantType =>
          if (const.value.isIntRange)
            Some(LIT(const.value.intValue) setPos pat.pos)
          else if (const.value.tag == StringTag)
            Some(LIT(const.value.stringValue) setPos pat.pos)
          else if (const.value.tag == NullTag)
            Some(LIT(null) setPos pat.pos)
          else None
        case _ => None
      }}

      def scrutRef(scrut: Symbol): Tree = scrut.tpe.dealiasWiden match {
        case subInt if subInt =:= IntTpe =>
          REF(scrut)
        case subInt if definitions.isNumericSubClass(subInt.typeSymbol, IntClass) =>
          REF(scrut) DOT nme.toInt
        case _ => REF(scrut)
      }

      object SwitchableTreeMaker extends SwitchableTreeMakerExtractor {
        def unapply(x: TreeMaker): Option[Tree] = x match {
          case EqualityTestTreeMaker(_, SwitchablePattern(const), _) => Some(const)
          case _ => None
        }
      }

      def isDefault(x: CaseDef): Boolean = x match {
        case CaseDef(Ident(nme.WILDCARD), EmptyTree, _) => true
        case _ => false
      }

      def defaultSym: Symbol = scrutSym
      def defaultBody: Tree  = matchFailGenOverride
        .map(gen => gen(REF(scrutSym)))
        .getOrElse(Throw(MatchErrorClass.tpe, REF(scrutSym)))
      def defaultCase(scrutSym: Symbol = defaultSym, guard: Tree = EmptyTree, body: Tree = defaultBody): CaseDef = { atPos(body.pos) {
        (DEFAULT IF guard) ==> body
      }}
    }

    override def emitSwitch(scrut: Tree, scrutSym: Symbol, cases: List[List[TreeMaker]], pt: Type, matchFailGenOverride: Option[Tree => Tree], unchecked: Boolean): Option[Tree] = { import CODE._
      val regularSwitchMaker = new RegularSwitchMaker(scrutSym, matchFailGenOverride, unchecked)
      // TODO: if patterns allow switch but the type of the scrutinee doesn't, cast (type-test) the scrutinee to the corresponding switchable type and switch on the result
      if (regularSwitchMaker.switchableTpe(scrutSym.tpe.dealiasWiden)) {
        val caseDefsWithDefault = regularSwitchMaker(cases map {c => (scrutSym, c)}, pt)
        if (caseDefsWithDefault.isEmpty) None // not worth emitting a switch.
        else {
          // match on scrutSym -- converted to an int if necessary -- not on scrut directly (to avoid duplicating scrut)
          Some(BLOCK(
            ValDef(scrutSym, scrut),
            Match(regularSwitchMaker.scrutRef(scrutSym), caseDefsWithDefault) // a switch
          ))
        }
      } else None
    }

    // for the catch-cases in a try/catch
    private object typeSwitchMaker extends SwitchMaker {
      val unchecked = false
      val alternativesSupported = false // TODO: needs either back-end support of flattening of alternatives during typers
      val canJump = false

      // TODO: there are more treemaker-sequences that can be handled by type tests
      // analyze the result of approximateTreeMaker rather than the TreeMaker itself
      object SwitchableTreeMaker extends SwitchableTreeMakerExtractor {
        def unapply(x: TreeMaker): Option[Tree] = x match {
          case tm@TypeTestTreeMaker(_, _, pt, _) if tm.isPureTypeTest => //  -- TODO: use this if binder does not occur in the body
            Some(Bind(tm.nextBinder, Typed(Ident(nme.WILDCARD), TypeTree(pt)) /* not used by back-end */))
          case _ =>
            None
        }
      }

      def isDefault(x: CaseDef): Boolean = x match {
        case CaseDef(Typed(Ident(nme.WILDCARD), tpt), EmptyTree, _) if (tpt.tpe =:= ThrowableTpe)          => true
        case CaseDef(Bind(_, Typed(Ident(nme.WILDCARD), tpt)), EmptyTree, _) if (tpt.tpe =:= ThrowableTpe) => true
        case CaseDef(Ident(nme.WILDCARD), EmptyTree, _)                                                          => true
        case _ => false
      }

      lazy val defaultSym: Symbol = freshSym(NoPosition, ThrowableTpe)
      def defaultBody: Tree       = Throw(CODE.REF(defaultSym))
      def defaultCase(scrutSym: Symbol = defaultSym, guard: Tree = EmptyTree, body: Tree = defaultBody): CaseDef = { import CODE._; atPos(body.pos) {
        (CASE (Bind(scrutSym, Typed(Ident(nme.WILDCARD), TypeTree(ThrowableTpe)))) IF guard) ==> body
      }}
    }

    override def unreachableTypeSwitchCase(cases: List[CaseDef]): Option[CaseDef] = typeSwitchMaker.unreachableCase(cases)

    // TODO: drop null checks
    override def emitTypeSwitch(bindersAndCases: List[(Symbol, List[TreeMaker])], pt: Type): Option[List[CaseDef]] = {
      val caseDefsWithDefault = typeSwitchMaker(bindersAndCases, pt)
      if (caseDefsWithDefault.isEmpty) None
      else Some(caseDefsWithDefault)
    }
  }

  trait MatchOptimizer extends OptimizedCodegen
                          with SwitchEmission
                          with CommonSubconditionElimination {
    override def optimizeCases(prevBinder: Symbol, cases: List[List[TreeMaker]], pt: Type, selectorPos: Position): (List[List[TreeMaker]], List[Tree]) = {
      val optCases = doCSE(prevBinder, cases, pt, selectorPos)
      (optCases, optCases.flatMap(flatCollect(_) { case tm: ReusedCondTreeMaker => tm.treesToHoist }))
    }
  }
}
