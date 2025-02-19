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

package scala
package tools
package nsc
package typechecker

import scala.collection.mutable
import symtab.Flags
import Mode._

/**
 *  A pattern match such as:
 *
 * {{{
 *   x match { case Foo(a, b) => ...}
 * }}}
 *
 *  Might match an instance of any of the following definitions of Foo.
 *  Note the analogous treatment between case classes and unapplies.
 *
 * {{{
 *    case class Foo(xs: Int*)
 *    case class Foo(a: Int, xs: Int*)
 *    case class Foo(a: Int, b: Int)
 *    case class Foo(a: Int, b: Int, xs: Int*)
 *
 *    object Foo { def unapplySeq(x: Any): Option[Seq[Int]] }
 *    object Foo { def unapplySeq(x: Any): Option[(Int, Seq[Int])] }
 *    object Foo { def unapply(x: Any): Option[(Int, Int)] }
 *    object Foo { def unapplySeq(x: Any): Option[(Int, Int, Seq[Int])] }
 * }}}
 */
trait PatternTypers {
  self: Analyzer =>

  import global._
  import definitions._

  trait PatternTyper {
    self: Typer =>

    import TyperErrorGen._
    import infer._

    // If the tree's symbol's type does not define an extractor, maybe the tree's type does.
    // this is the case when we encounter an arbitrary tree as the target of an unapply call
    // (rather than something that looks like a constructor call.)
    // (happens due to wrapClassTagUnapply)
    private def hasUnapplyMember(tpe: Type): Boolean   = reallyExists(unapplyMember(tpe))
    private def hasUnapplyMember(sym: Symbol): Boolean = hasUnapplyMember(sym.tpe_*)

    // ad-hoc overloading resolution to deal with unapplies and case class constructors
    // If some but not all alternatives survive filtering the tree's symbol with `p`,
    // then update the tree's symbol and type to exclude the filtered out alternatives.
    private def inPlaceAdHocOverloadingResolution(fun: Tree)(p: Symbol => Boolean): Tree = fun.symbol filter p match {
      case sym if sym.exists && (sym ne fun.symbol) => fun setSymbol sym modifyType (tp => filterOverloadedAlts(tp)(p))
      case _                                        => fun
    }
    private def filterOverloadedAlts(tpe: Type)(p: Symbol => Boolean): Type = tpe match {
      case OverloadedType(pre, alts) => overloadedType(pre, alts filter p)
      case tp                        => tp
    }

    def applyTypeToWildcards(tp: Type) = tp match {
      case tr @ TypeRef(pre, sym, args) if args.nonEmpty =>
        // similar to `typedBind`
        def wld = context.owner.newAbstractType(tpnme.WILDCARD, sym.pos) setInfo TypeBounds.empty
        copyTypeRef(tr, pre, sym, args.map(_ => wld.tpe))
      case t => t
    }

    def typedConstructorPattern(fun0: Tree, pt: Type): Tree = {
      // Do some ad-hoc overloading resolution and update the tree's symbol and type
      // do not update the symbol if the tree's symbol's type does not define an unapply member
      // (e.g. since it's some method that returns an object with an unapply member)
      val fun         = inPlaceAdHocOverloadingResolution(fun0)(hasUnapplyMember)
      val canElide    = treeInfo.isQualifierSafeToElide(fun)
      val caseClass   = companionSymbolOf(fun.tpe.typeSymbol.sourceModule, context)
      val member      = unapplyMember(fun.tpe)
      def resultType  = (fun.tpe memberType member).finalResultType
      def isEmptyType = resultOfIsEmpty(resultType)

      def useConstructor = (
        // Dueling test cases: pos/overloaded-unapply.scala, run/case-class-23.scala, pos/t5022.scala
        // Use the case class constructor if (after canElide + isCase) the unapply method:
        // (1) doesn't exist, e.g. case classes with 23+ params. run/case-class-23.scala
        // (2) is the synthetic case class one, i.e. not user redefined. pos/t11252.scala
        // (3a) is overloaded and the synthetic case class one is still present (i.e. not suppressed) pos/t12250.scala
        // (3b) the scrutinee type is the case class (not a subtype). pos/overloaded-unapply.scala vs pos/t12250b.scala
        canElide && caseClass.isCase && (
             member == NoSymbol                                                  // (1)
          || member.isSynthetic                                                  // (2)
          || (member.alternatives.exists(_.isSynthetic) && caseClass.tpe =:= pt) // (3a)(3b)
        )
      )

      def isOkay      = (
           resultType.isErroneous
        || (resultType <:< BooleanTpe)
        || (isEmptyType <:< BooleanTpe)
        || member.isMacro
        || member.isOverloaded // the whole overloading situation is over the rails
      )

      // if we're already failing, no need to emit another error here
      if (fun.tpe.isErroneous)
        fun
      else if (useConstructor)
        logResult(s"convertToCaseConstructor($fun, $caseClass, pt=$pt)")(convertToCaseConstructor(fun, caseClass, pt))
      else if (!reallyExists(member))
        CaseClassConstructorError(fun, s"${fun.symbol} is not a case class, nor does it have a valid unapply/unapplySeq member")
      else if (isOkay)
        fun
      else if (isEmptyType == NoType)
        CaseClassConstructorError(fun, s"an unapply result must have a member `def isEmpty: Boolean`")
      else
        CaseClassConstructorError(fun, s"an unapply result must have a member `def isEmpty: Boolean` (found: `def isEmpty: $isEmptyType`)")
    }

    def typedArgsForFormals(args: List[Tree], formals: List[Type], mode: Mode): List[Tree] = {
      def typedArgWithFormal(arg: Tree, pt: Type) = {
        if (isByNameParamType(pt))
          typedArg(arg, mode, mode.onlySticky, dropByName(pt))
        else
          typedArg(arg, mode, mode.onlySticky | BYVALmode, pt)
      }
      if (formals.isEmpty) Nil
      else {
        val lastFormal = formals.last
        val isRepeated = isRepeatedParamType(lastFormal)
        if (isRepeated) {
          val fixed = formals.init
          val elem = dropRepeated(lastFormal)
          val front = map2(args, fixed)(typedArgWithFormal)
          val rest = context withinStarPatterns (args drop front.length map (typedArgWithFormal(_, elem)))

          front ::: rest
        } else {
          map2(args, formals)(typedArgWithFormal)
        }
      }
    }

    private def boundedArrayType(bound: Type): Type = {
      val tparam = context.owner.freshExistential("", 0) setInfo (TypeBounds upper bound)
      newExistentialType(tparam :: Nil, arrayType(tparam.tpe_*))
    }

    protected def typedStarInPattern(tree: Tree, mode: Mode, pt: Type) = {
      val Typed(expr, tpt) = tree: @unchecked
      val exprTyped = typed(expr, mode)
      val baseClass = exprTyped.tpe.typeSymbol match {
        case ArrayClass   => ArrayClass
        case NothingClass => NothingClass
        case NullClass    => NullClass
        case _            => SeqClass
      }
      val starType = baseClass match {
        case ArrayClass if isPrimitiveValueType(pt) || !isFullyDefined(pt) => arrayType(pt)
        case ArrayClass                                                    => boundedArrayType(pt)
        case NullClass                                                     => seqType(NothingTpe)
        case _                                                             => seqType(pt)
      }
      val exprAdapted = adapt(exprTyped, mode, starType)
      exprAdapted.tpe.baseType(baseClass) match {
        case TypeRef(_, _, elemtp :: Nil)   => treeCopy.Typed(tree, exprAdapted, tpt setType elemtp) setType elemtp
        case _ if baseClass eq NothingClass => exprAdapted
        case _ if baseClass eq NullClass    => treeCopy.Typed(tree, exprAdapted, tpt.setType(NothingTpe)).setType(NothingTpe)
        case _                              => setError(tree)
      }
    }

    protected def typedInPattern(tree: Typed, mode: Mode, pt: Type) = {
      val Typed(expr, tpt) = tree
      val tptTyped  = typedType(tpt, mode)
      val tpe       = tptTyped.tpe
      val exprTyped = typed(expr, mode, tpe.deconst)
      val extractor = extractorForUncheckedType(tpt.pos, tpe)

      val canRemedy = tpe match {
        case RefinedType(_, decls) if !decls.isEmpty                 => false
        case RefinedType(parents, _) if parents exists isUncheckable => false
        case _                                                       => extractor.nonEmpty
      }

      val ownType   = inferTypedPattern(tptTyped, tpe, pt, canRemedy = canRemedy, isUnapply = false)
      val treeTyped = treeCopy.Typed(tree, exprTyped, tptTyped) setType ownType

      extractor match {
        case EmptyTree => treeTyped
        case _         => wrapClassTagUnapply(treeTyped, extractor, tpe)
      }
    }

    private class VariantToSkolemMap extends VariancedTypeMap {
      private val skolemBuffer = mutable.ListBuffer[TypeSymbol]()

      // !!! FIXME - skipping this when variance.isInvariant allows unsoundness, see scala/bug#5189
      // Test case which presently requires the exclusion is run/gadts.scala.
      def eligible(tparam: Symbol) = (
           tparam.isTypeParameterOrSkolem
        && tparam.owner.isTerm
        && !variance.isInvariant
      )

      def skolems = try skolemBuffer.toList finally skolemBuffer.clear()
      def apply(tp: Type): Type = mapOver(tp) match {
        case TypeRef(NoPrefix, tpSym, Nil) if eligible(tpSym) =>
          val bounds = genPolyType(tpSym.typeParams,
            if (variance.isInvariant) tpSym.tpe.bounds
            else if (variance.isPositive) TypeBounds.upper(tpSym.tpe)
            else TypeBounds.lower(tpSym.tpe)
          )
          // origin must be the type param so we can deskolemize
          val skolem = context.owner.newGADTSkolem(freshTypeName("?" + tpSym.name), tpSym, bounds)
          skolemBuffer += skolem
          logResult(s"Created gadt skolem $skolem: ${skolem.tpeHK} to stand in for $tpSym")(skolem.tpeHK)
        case tp1 => tp1
      }
    }

    /*
     * To deal with the type slack between actual (run-time) types and statically known types, for each abstract type T,
     * reflect its variance as a skolem that is upper-bounded by T (covariant position), or lower-bounded by T (contravariant).
     *
     * Consider the following example:
     *
     *  class AbsWrapperCov[+A]
     *  case class Wrapper[B](x: Wrapped[B]) extends AbsWrapperCov[B]
     *
     *  def unwrap[T](x: AbsWrapperCov[T]): Wrapped[T] = x match {
     *    case Wrapper(wrapped) => // Wrapper's type parameter must not be assumed to be equal to T, it's *upper-bounded* by it
     *      wrapped // : Wrapped[_ <: T]
     *  }
     *
     * this method should type check if and only if Wrapped is covariant in its type parameter
     *
     * when inferring Wrapper's type parameter B from x's type AbsWrapperCov[T],
     * we must take into account that x's actual type is AbsWrapperCov[Tactual] forSome {type Tactual <: T}
     * as AbsWrapperCov is covariant in A -- in other words, we must not assume we know T exactly, all we know is its upper bound
     *
     * since method application is the only way to generate this slack between run-time and compile-time types (TODO: right!?),
     * we can simply replace skolems that represent method type parameters as seen from the method's body
     * by other skolems that are (upper/lower)-bounded by that type-parameter skolem
     * (depending on the variance position of the skolem in the statically assumed type of the scrutinee, pt)
     *
     * see test/files/../t5189*.scala
     */
    private def convertToCaseConstructor(tree: Tree, caseClass: Symbol, ptIn: Type): Tree = {
      val variantToSkolem     = new VariantToSkolemMap

      //  `caseClassType` is the prefix from which we're seeing the constructor info, so it must be kind *.
      // Need the `initialize` call to make sure we see any type params.
      val caseClassType       = caseClass.initialize.tpe_*.asSeenFrom(tree.tpe.prefix, caseClass.owner)
      assert(!caseClassType.isHigherKinded, s"Unexpected type constructor $caseClassType")

      // If the case class is polymorphic, need to capture those type params in the type that we relativize using asSeenFrom,
      // as they may also be sensitive to the prefix (see test/files/pos/t11103.scala).
      // Note that undetParams may thus be different from caseClass.typeParams.
      // (For a monomorphic case class, GenPolyType will not create/destruct a PolyType.)
      val GenPolyType(undetparams, caseConstructorType) =
        GenPolyType(caseClass.typeParams, caseClass.primaryConstructor.info).asSeenFrom(caseClassType, caseClass)

      // log(s"convertToCaseConstructor(${tree.tpe}, $caseClass, $ptIn) // $caseClassType // ${caseConstructorType.typeParams.map(_.info)}")

      val tree1 = TypeTree(caseConstructorType) setOriginal tree

      // have to open up the existential and put the skolems in scope
      // can't simply package up pt in an ExistentialType, because that takes us back to square one (List[_ <: T] == List[T] due to covariance)
      val ptSafe   = logResult(s"case constructor from (${tree.summaryString}, $caseClassType, $ptIn)")(variantToSkolem(ptIn))
      val freeVars = variantToSkolem.skolems

      // use "tree" for the context, not context.tree: don't make another CaseDef context,
      // as instantiateTypeVar's bounds would end up there
      val ctorContext = context.makeNewScope(tree, context.owner)
      freeVars.foreach(ctorContext.scope.enter(_))
      newTyper(ctorContext).infer.inferConstructorInstance(tree1, undetparams, ptSafe)

      // simplify types without losing safety,
      // so that we get rid of unnecessary type slack, and so that error messages don't unnecessarily refer to skolems
      val extrapolator = new ExistentialExtrapolation(freeVars)
      def extrapolate(tp: Type) = extrapolator extrapolate tp

      // once the containing CaseDef has been type checked (see typedCase),
      // tree1's remaining type-slack skolems will be deskolemized (to the method type parameter skolems)
      tree1 modifyType {
        case MethodType(ctorArgs, restpe) => // ctorArgs are actually in a covariant position, since this is the type of the subpatterns of the pattern represented by this Apply node
          ctorArgs foreach (_ modifyInfo extrapolate)
          copyMethodType(tree1.tpe, ctorArgs, extrapolate(restpe)) // no need to clone ctorArgs, this is OUR method type
        case tp => tp
      }
    }

    def doTypedUnapply(tree: Tree, funOrig: Tree, funOverloadResolved: Tree, args: List[Tree], mode: Mode, pt: Type): Tree = {
      def errorTree: Tree = treeCopy.Apply(tree, funOrig, args) setType ErrorType

      if (args.lengthCompare(MaxTupleArity) > 0) {
        TooManyArgsPatternError(funOverloadResolved); errorTree
      } else {
        val extractorPos = funOverloadResolved.pos
        val extractorTp  = funOverloadResolved.tpe

        val unapplyMethod = unapplyMember(extractorTp)
        val unapplyType = extractorTp memberType unapplyMethod

        lazy val remedyUncheckedWithClassTag = extractorForUncheckedType(extractorPos, firstParamType(unapplyType))
        def canRemedy = remedyUncheckedWithClassTag != EmptyTree

        val selectorDummySym =
          context.owner.newValue(nme.SELECTOR_DUMMY, extractorPos, Flags.SYNTHETIC) setInfo {
            if (isApplicableSafe(Nil, unapplyType, pt :: Nil, WildcardType)) pt
            else {
              def freshArgType(tp: Type): Type = tp match {
                case MethodType(param :: _, _) => param.tpe
                case PolyType(tparams, restpe) => createFromClonedSymbols(tparams, freshArgType(restpe))(genPolyType)
                case OverloadedType(_, _)      => OverloadedUnapplyError(funOverloadResolved); ErrorType
                case _                         => UnapplyWithSingleArgError(funOverloadResolved); ErrorType
              }
              val GenPolyType(freeVars, unappFormal) = freshArgType(unapplyType.skolemizeExistential(context.owner, tree))
              val unapplyContext = context.makeNewScope(tree, context.owner)
              freeVars.foreach(unapplyContext.scope.enter(_))
              val pattp = newTyper(unapplyContext).infer.inferTypedPattern(tree, unappFormal, pt, canRemedy = canRemedy, isUnapply = true)
              // turn any unresolved type variables in freevars into existential skolems
              val skolems = freeVars.map(fv => unapplyContext.owner.newExistentialSkolem(fv, fv))
              pattp.substSym(freeVars, skolems)
            }
          }

        // Clearing the type is necessary so that ref will be stabilized; see scala/bug#881.
        val selectUnapply = Select(funOverloadResolved.clearType(), unapplyMethod)

        // NOTE: The symbol of unapplyArgTree (`<unapply-selector>`) may be referenced in `fun1.tpe`
        // the pattern matcher deals with this in ExtractorCallRegular -- SI-6130
        val unapplyArg = Ident(selectorDummySym) updateAttachment SubpatternsAttachment(args) // attachment is for quasiquotes

        val typedApplied = typedPos(extractorPos)(Apply(selectUnapply, unapplyArg :: Nil))

        if (typedApplied.tpe.isErroneous || unapplyMethod.isMacro && !typedApplied.isInstanceOf[Apply]) {
          if (unapplyMethod.isMacro) {
            if (isBlackbox(unapplyMethod)) BlackboxExtractorExpansion(tree)
            else WrongShapeExtractorExpansion(tree)
          }
          errorTree
        } else {
          val unapplyArgTypeInferred = selectorDummySym.tpe_*
          // the union of the expected type and the inferred type of the argument to unapply
          val extractedTp = glb(ensureFullyDefined(pt) :: unapplyArgTypeInferred :: Nil)
          val formals = patmat.unapplyFormals(typedApplied, args)(context)
          val typedUnapply = UnApply(typedApplied, typedArgsForFormals(args, formals, mode)) setPos tree.pos setType extractedTp

          if (canRemedy && !(typedApplied.symbol.owner isNonBottomSubClass ClassTagClass))
            wrapClassTagUnapply(typedUnapply, remedyUncheckedWithClassTag, extractedTp)
          else
            typedUnapply
        }
      }
    }

    def wrapClassTagUnapply(uncheckedPattern: Tree, classTagExtractor: Tree, pt: Type): Tree = {
      // TODO: disable when in unchecked match
      // we don't create a new Context for a Match, so find the CaseDef,
      // then go out one level and navigate back to the match that has this case
      val args = List(uncheckedPattern)
      val app  = atPos(uncheckedPattern.pos)(Apply(classTagExtractor, args))
      // must call doTypedUnapply directly, as otherwise we get undesirable rewrites
      // and re-typechecks of the target of the unapply call in PATTERNmode,
      // this breaks down when the classTagExtractor (which defines the unapply member) is not a simple reference to an object,
      // but an arbitrary tree as is the case here
      val res = doTypedUnapply(app, classTagExtractor, classTagExtractor, args, PATTERNmode, pt)

      log(sm"""
        |wrapClassTagUnapply {
        |  pattern: $uncheckedPattern
        |  extract: $classTagExtractor
        |       pt: $pt
        |      res: $res
        |}""".trim)

      res
    }

    // if there's a ClassTag that allows us to turn the unchecked type test for `pt` into a checked type test
    // return the corresponding extractor (an instance of ClassTag[`pt`])
    def extractorForUncheckedType(pos: Position, pt: Type): Tree = {
      if (isPastTyper || (pt eq NoType)) EmptyTree else {
        pt match {
          case RefinedType(parents, decls) if !decls.isEmpty || (parents exists isUncheckable) => return EmptyTree
          case _                                                                               =>
        }
        // only look at top-level type, can't (reliably) do anything about unchecked type args (in general)
        // but at least make a proper type before passing it elsewhere
        val pt1 = applyTypeToWildcards(pt.dealiasWiden)
        if (isCheckable(pt1)) EmptyTree
        else resolveClassTag(pos, pt1) match {
          case tree if unapplyMember(tree.tpe).exists => tree
          case _                                      => devWarning(s"Cannot create runtime type test for $pt1") ; EmptyTree
        }
      }
    }
  }
}
