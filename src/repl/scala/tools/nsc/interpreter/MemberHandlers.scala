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

package scala.tools.nsc.interpreter

import scala.language.implicitConversions

import scala.collection.mutable
import scala.reflect.internal.Precedence

trait MemberHandlers {
  val intp: IMain

  // show identity hashcode of objects in vals
  final val showObjIds = false

  import intp.{ Request, global, naming, nameToCode, typeToCode }
  import global._
  import naming._

  import ReplStrings.{string2codeQuoted, string2code, any2stringOf, quotedString}

  private def codegenln(leadingPlus: Boolean, xs: String*): String = codegen(leadingPlus, (xs ++ Array("\n")): _*)
  private def codegenln(xs: String*): String = codegenln(leadingPlus = true, xs: _*)
  private def codegen(leadingPlus: Boolean, xs: String*): String = {
    val front = if (leadingPlus) "+ " else ""
    front + (xs map string2codeQuoted mkString " + ")
  }
  private implicit def name2string(name: Name): String = name.toString

  /** A traverser that finds all mentioned identifiers, i.e. things
   *  that need to be imported.  It might return extra names.
   */
  private class ImportVarsTraverser extends Traverser {
    val importVars = new mutable.HashSet[Name]()

    override def traverse(ast: Tree) = ast match {
      case Ident(name) =>
        // XXX this is obviously inadequate but it's going to require some effort
        // to get right.
        if (name.toString startsWith "x$") ()
        else {
          importVars += name
          // Needed to import `xxx` during line 2 of:
          //   scala> val xxx = ""
          //   scala> def foo: x<TAB>
          if (name.endsWith(IMain.DummyCursorFragment)) {
            val stripped = name.stripSuffix(IMain.DummyCursorFragment)
            importVars += stripped
          }
        }
      case _        => super.traverse(ast)
    }
  }
  private object ImportVarsTraverser {
    def apply(member: Tree) = {
      val ivt = new ImportVarsTraverser()
      ivt traverse member
      ivt.importVars.toList
    }
  }

  private def isTermMacro(ddef: DefDef): Boolean = ddef.mods.isMacro

  def chooseHandler(member: Tree): MemberHandler = member match {
    case member: DefDef if isTermMacro(member) => new TermMacroHandler(member)
    case member: DefDef                        => new DefHandler(member)
    case member: ValDef                        => new ValHandler(member)
    case member: ModuleDef                     => new ModuleHandler(member)
    case member: ClassDef                      => new ClassHandler(member)
    case member: TypeDef                       => new TypeAliasHandler(member)
    case member: Assign                        => AssignHandler(member)
    case member @ Apply(Select(_, op), _)
    if Precedence(op.decoded).level == 0       => AssignHandler(member)
    case member: Import                        => new ImportHandler(member.duplicate) // duplicate because the same tree will be type checked (which loses info)
    case DocDef(_, documented)                 => chooseHandler(documented)
    case member                                => new GenericHandler(member)
  }

  sealed abstract class MemberDefHandler(override val member: MemberDef) extends MemberHandler(member) {
    override def name: Name = member.name
    def mods: Modifiers     = member.mods
    def keyword             = member.keyword
    def prettyName          = name.decode

    override def definesImplicit = member.mods.isImplicit
    override def definesTerm: Option[TermName] = Some(name.toTermName) filter (_ => name.isTermName)
    override def definesType: Option[TypeName] = Some(name.toTypeName) filter (_ => name.isTypeName)
    override def definedSymbols = if (symbol.exists) symbol :: Nil else Nil
  }

  /** Class to handle one member among all the members included
   *  in a single interpreter request.
   */
  sealed abstract class MemberHandler(val member: Tree) {
    def name: Name      = nme.NO_NAME
    def path            = intp.originalPath(symbol)
    def symbol          = if (member.symbol eq null) NoSymbol else member.symbol
    def definesImplicit = false
    def definesValueClass = false
    def definesValue    = false

    def definesTerm     = Option.empty[TermName]
    def definesType     = Option.empty[TypeName]

    private lazy val _referencedNames = ImportVarsTraverser(member)
    def referencedNames = _referencedNames
    def importedNames   = List[Name]()
    def definedNames    = definesTerm.toList ++ definesType.toList
    def definedSymbols  = List[Symbol]()

    def resultExtractionCode(req: Request): String = ""

    private def shortName = this.getClass.toString.split('.').last
    override def toString = shortName + referencedNames.mkString(" (refs: ", ", ", ")")
  }

  class GenericHandler(member: Tree) extends MemberHandler(member)


  class ValHandler(member: ValDef) extends MemberDefHandler(member) {
    val maxStringElements = 1000  // no need to mkString billions of elements
    override def definesValue = true

    override def resultExtractionCode(req: Request): String = {
      val isInternal = isUserVarName(name) && req.lookupTypeOf(name) == "Unit"
      if (!mods.isPublic || isInternal) ""
      else {
        // if this is a lazy val we avoid evaluating it here
        val resultString =
          if (mods.isLazy) quotedString(" // unevaluated")
          else quotedString(" = ") + " + " + any2stringOf(path, maxStringElements)

        val varOrValOrLzy =
          if (mods.isMutable) "var"
          else if (mods.isLazy) "lazy val"
          else "val"

        val nameString = {
          nameToCode(string2code(prettyName)) + (
            if (showObjIds) s"""" + f"@$${System.identityHashCode($path)}%8x" + """"
            else ""
          )
        }

        val typeString = typeToCode(string2code(req.typeOf(name)))

        s""" + "$varOrValOrLzy $nameString: $typeString" + $resultString"""
      }
    }
  }

  class DefHandler(member: DefDef) extends MemberDefHandler(member) {
    override def definesValue = flattensToEmpty(member.vparamss) // true if 0-arity
    override def resultExtractionCode(req: Request) =
      if (mods.isPublic) codegenln(s"def ${req.defTypeOf(name)}") else ""
  }

  abstract class MacroHandler(member: DefDef) extends MemberDefHandler(member) {
    override def referencedNames = super.referencedNames.flatMap(name => List(name.toTermName, name.toTypeName))
    override def definesValue = false
    override def definesTerm: Option[TermName] = Some(name.toTermName)
    override def definesType: Option[TypeName] = None
    override def resultExtractionCode(req: Request) = if (mods.isPublic) codegenln(notification(req)) else ""
    def notification(req: Request): String
  }

  class TermMacroHandler(member: DefDef) extends MacroHandler(member) {
    def notification(req: Request) = s"def ${req.defTypeOf(name)}"
  }

  class AssignHandler private (member: Tree, lhs: Tree) extends MemberHandler(member) {
    override def resultExtractionCode(req: Request) =
      codegenln(s"// mutated $lhs")
  }
  object AssignHandler {
    def apply(member: Assign) = new AssignHandler(member, member.lhs)
    def apply(member: Apply) = member match {
      case Apply(Select(qual, op), _) if Precedence(op.decoded).level == 0 => new AssignHandler(member, qual)
      case _ => new GenericHandler(member)
    }
  }

  class ModuleHandler(module: ModuleDef) extends MemberDefHandler(module) {
    override def definesTerm = Some(name.toTermName)
    override def definesValue = true
    override def definesValueClass = {
      var foundValueClass = false
      new Traverser {
        override def traverse(tree: Tree): Unit = tree match {
          case _ if foundValueClass                 => ()
          case cdef: ClassDef if isValueClass(cdef) => foundValueClass = true
          case mdef: ModuleDef                      => traverseStats(mdef.impl.body, mdef.impl.symbol)
          case _                                    => () // skip anything else
        }
      }.traverse(module)
      foundValueClass
    }

    override def resultExtractionCode(req: Request) = codegenln(s"object $name")
  }

  private def isValueClass(cdef: ClassDef) = cdef.impl.parents match {
    case Ident(tpnme.AnyVal) :: _ => true // approximating with a syntactic check
    case _                        => false
  }

  class ClassHandler(member: ClassDef) extends MemberDefHandler(member) {
    override def definedSymbols = List(symbol, symbol.companionSymbol) filterNot (_ == NoSymbol)
    override def definesType = Some(name.toTypeName)
    override def definesTerm = Some(name.toTermName) filter (_ => mods.isCase)
    override def definesValueClass = isValueClass(member)

    override def resultExtractionCode(req: Request) = codegenln(s"$keyword $name")
  }

  class TypeAliasHandler(member: TypeDef) extends MemberDefHandler(member) {
    private def isAlias = mods.isPublic && treeInfo.isAliasTypeDef(member)
    override def definesType = Some(name.toTypeName) filter (_ => isAlias)

    override def resultExtractionCode(req: Request) = codegenln(s"type $name")
  }

  class ImportHandler(imp: Import) extends MemberHandler(imp) {
    val Import(expr, selectors) = imp

    def targetType = intp.global.rootMirror.getModuleIfDefined("" + expr) match {
      case NoSymbol => intp.typeOfExpression("" + expr)
      case sym      => sym.tpe
    }

    private def isFlattenedSymbol(sym: Symbol) =
      sym.owner.isPackageClass &&
        sym.name.containsName(nme.NAME_JOIN_STRING) &&
        sym.owner.info.member(sym.name.take(sym.name.indexOf(nme.NAME_JOIN_STRING))) != NoSymbol

    private def importableTargetMembers =
      importableMembers(exitingTyper(targetType)).filterNot(isFlattenedSymbol).toList

    // non-wildcard imports
    private def individualSelectors = selectors.filter(_.isSpecific)

    /** Whether this import includes a wildcard import */
    val importsWildcard = selectors.exists(_.isWildcard)

    def implicitSymbols = importedSymbols filter (_.isImplicit)
    def importedSymbols = individualSymbols ++ wildcardSymbols

    lazy val importableSymbolsWithRenames = {
      val selectorRenameMap: mutable.HashMap[Name, Name] = mutable.HashMap.empty[Name, Name]
      individualSelectors foreach { x =>
        selectorRenameMap.put(x.name.toTermName, x.rename.toTermName)
        selectorRenameMap.put(x.name.toTypeName, x.rename.toTypeName)
      }
      importableTargetMembers flatMap (m => selectorRenameMap.get(m.name) map (m -> _))
    }

    lazy val individualSymbols: List[Symbol] = importableSymbolsWithRenames map (_._1)
    lazy val wildcardSymbols: List[Symbol]   = if (importsWildcard) importableTargetMembers else Nil

    /** Complete list of names imported by a wildcard */
    lazy val wildcardNames: List[Name]   = wildcardSymbols map (_.name)
    lazy val individualNames: List[Name] = importableSymbolsWithRenames map (_._2)

    /** The names imported by this statement */
    override lazy val importedNames: List[Name] = wildcardNames ++ individualNames
    lazy val importsSymbolNamed: Set[String] = importedNames.map(_.toString).toSet

    def importString = imp.toString
    override def resultExtractionCode(req: Request) = codegenln(importString) + "\n"
  }
}
