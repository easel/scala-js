/*                     __                                               *\
**     ________ ___   / /  ___      __ ____  Scala.js tools             **
**    / __/ __// _ | / /  / _ | __ / // __/  (c) 2013-2014, LAMP/EPFL   **
**  __\ \/ /__/ __ |/ /__/ __ |/_// /_\ \    http://scala-js.org/       **
** /____/\___/_/ |_/____/_/ | |__/ /____/                               **
**                          |/____/                                     **
\*                                                                      */


package org.scalajs.core.tools.linker.frontend

import scala.annotation.tailrec

import scala.collection.mutable
import scala.util.Try

import org.scalajs.core.tools.sem._
import org.scalajs.core.tools.javascript.ESLevel
import org.scalajs.core.tools.logging._
import org.scalajs.core.tools.io._

import org.scalajs.core.tools.linker._
import org.scalajs.core.tools.linker.checker._
import org.scalajs.core.tools.linker.analyzer._

import org.scalajs.core.ir
import ir.Infos
import ir.Trees._
import ir.Types._
import ir.ClassKind
import ir.Hashers
import ir.Position
import ir.Definitions

import Analysis._

/** Links the information from [[io.VirtualScalaJSIRFile]]s into
 *  [[LinkedClass]]es. Does a dead code elimination pass.
 */
final class BaseLinker(semantics: Semantics, esLevel: ESLevel,
    considerPositions: Boolean) {

  private type TreeProvider = String => (ClassDef, Option[String])

  @deprecated("Use the overload with explicit module initializers.", "0.6.15")
  def link(irInput: Seq[VirtualScalaJSIRFile], logger: Logger,
      symbolRequirements: SymbolRequirement, checkIR: Boolean): LinkingUnit = {
    link(irInput, Nil, logger, symbolRequirements, checkIR)
  }

  def link(irInput: Seq[VirtualScalaJSIRFile],
      moduleInitializers: Seq[ModuleInitializer], logger: Logger,
      symbolRequirements: SymbolRequirement, checkIR: Boolean): LinkingUnit = {
    linkInternal(irInput, moduleInitializers, logger, symbolRequirements,
        bypassLinkingErrors = false, checkIR = checkIR)
  }

  @deprecated(
      "Bypassing linking errors will not be possible in the next major version. " +
      "Use the overload without the bypassLinkingError parameter instead.",
      "0.6.6")
  def link(irInput: Seq[VirtualScalaJSIRFile], logger: Logger,
      symbolRequirements: SymbolRequirement, bypassLinkingErrors: Boolean,
      checkIR: Boolean): LinkingUnit = {
    linkInternal(irInput, Nil, logger, symbolRequirements,
        bypassLinkingErrors, checkIR)
  }

  @deprecated(
      "Bypassing linking errors will not be possible in the next major version. " +
      "Use the overload without the bypassLinkingError parameter instead.",
      "0.6.6")
  def link(irInput: Seq[VirtualScalaJSIRFile],
      moduleInitializers: Seq[ModuleInitializer], logger: Logger,
      symbolRequirements: SymbolRequirement, bypassLinkingErrors: Boolean,
      checkIR: Boolean): LinkingUnit = {
    linkInternal(irInput, moduleInitializers, logger, symbolRequirements,
        bypassLinkingErrors, checkIR)
  }

  // Non-deprecated version to be called from `LinkerFrontend`
  private[frontend] def linkInternal(irInput: Seq[VirtualScalaJSIRFile],
      moduleInitializers: Seq[ModuleInitializer], logger: Logger,
      symbolRequirements: SymbolRequirement, bypassLinkingErrors: Boolean,
      checkIR: Boolean): LinkingUnit = {

    val infosBuilder = List.newBuilder[Infos.ClassInfo]
    val encodedNameToFile = mutable.Map.empty[String, VirtualScalaJSIRFile]

    for (irFile <- irInput) {
      val info = irFile.info

      // Remove duplicates. Just like the JVM
      if (!encodedNameToFile.contains(info.encodedName)) {
        infosBuilder += info
        encodedNameToFile += info.encodedName -> irFile
      }
    }

    val infos = infosBuilder.result()

    val getTree: TreeProvider = { name =>
      val pf = encodedNameToFile(name)
      (pf.tree, pf.version)
    }

    linkInternal(infos, getTree, moduleInitializers, logger, symbolRequirements,
        bypassLinkingErrors, checkIR)
  }

  private def linkInternal(infoInput: List[Infos.ClassInfo],
      getTree: TreeProvider, moduleInitializers: Seq[ModuleInitializer],
      logger: Logger, symbolRequirements: SymbolRequirement,
      bypassLinkingErrors: Boolean, checkIR: Boolean): LinkingUnit = {

    if (checkIR) {
      logger.time("Linker: Check Infos") {
        val infoAndTrees =
          infoInput.map(info => (info, getTree(info.encodedName)._1))
        val errorCount = InfoChecker.check(infoAndTrees, logger)
        if (errorCount != 0) {
          throw new LinkingException(
              s"There were $errorCount Info checking errors.")
        }
      }
    }

    val analysis = logger.time("Linker: Compute reachability") {
      val allSymbolRequirements = {
        symbolRequirements ++
        ModuleInitializer.toSymbolRequirement(moduleInitializers)
      }
      Analyzer.computeReachability(semantics, allSymbolRequirements, infoInput,
          allowAddingSyntheticMethods = true)
    }

    if (analysis.errors.nonEmpty) {
      // TODO Make it always fatal when we can get rid of bypassLinkingErrors
      val fatal = !bypassLinkingErrors || analysis.errors.exists {
        case _: Analysis.MissingJavaLangObjectClass => true
        case _: Analysis.CycleInInheritanceChain    => true
        case _                                      => false
      }

      val linkingErrLevel = if (fatal) Level.Error else Level.Warn
      val maxDisplayErrors = {
        val propName = "org.scalajs.core.tools.linker.maxlinkingerrors"
        Try(System.getProperty(propName, "20").toInt).getOrElse(20).max(1)
      }

      analysis.errors
        .take(maxDisplayErrors)
        .foreach(logError(_, logger, linkingErrLevel))

      val skipped = analysis.errors.size - maxDisplayErrors
      if (skipped > 0)
        logger.log(linkingErrLevel, s"Not showing $skipped more linking errors")

      if (fatal)
        throw new LinkingException("There were linking errors")
    }

    val linkResult = logger.time("Linker: Assemble LinkedClasses") {
      assemble(infoInput, getTree, moduleInitializers, analysis)
    }

    // Make sure we don't export to the same name twice.
    checkConflictingExports(linkResult, logger, bypassLinkingErrors)

    if (checkIR) {
      logger.time("Linker: Check IR") {
        if (linkResult.isComplete) {
          val errorCount = IRChecker.check(linkResult, logger)
          if (errorCount != 0) {
            throw new LinkingException(
                s"There were $errorCount IR checking errors.")
          }
        } else {
          throw new LinkingException(
              "Could not check IR because there were linking errors.")
        }
      }
    }

    linkResult
  }

  private def assemble(infoInput: List[Infos.ClassInfo], getTree: TreeProvider,
      moduleInitializers: Seq[ModuleInitializer], analysis: Analysis) = {
    val infoByName = Map(infoInput.map(c => c.encodedName -> c): _*)

    def optClassDef(analyzerInfo: Analysis.ClassInfo) = {
      val encodedName = analyzerInfo.encodedName

      def optDummyParent =
        if (!analyzerInfo.isAnySubclassInstantiated) None
        else Some(LinkedClass.dummyParent(encodedName, Some("dummy")))

      infoByName.get(encodedName).map { info =>
        val (tree, version) = getTree(encodedName)
        val newVersion = version.map("real" + _) // avoid collision with dummy
        linkedClassDef(info, tree, analyzerInfo, newVersion, getTree, analysis)
      }.orElse(optDummyParent)
    }

    val linkedClassDefs = for {
      classInfo <- analysis.classInfos.values
      if classInfo.isNeededAtAll
      linkedClassDef <- optClassDef(classInfo)
    } yield linkedClassDef

    new LinkingUnit(semantics, esLevel, linkedClassDefs.toList, infoByName,
        moduleInitializers.toList, analysis.allAvailable)
  }

  /** Takes a Infos, a ClassDef and DCE infos to construct a stripped down
   *  LinkedClassDef */
  private def linkedClassDef(info: Infos.ClassInfo, classDef: ClassDef,
      analyzerInfo: Analysis.ClassInfo, version: Option[String],
      getTree: TreeProvider, analysis: Analysis) = {
    import ir.Trees._

    val memberInfoByStaticAndName =
      Map(info.methods.map(m => (m.isStatic, m.encodedName) -> m): _*)

    val fields = mutable.Buffer.empty[FieldDef]
    val staticMethods = mutable.Buffer.empty[LinkedMember[MethodDef]]
    val memberMethods = mutable.Buffer.empty[LinkedMember[MethodDef]]
    val abstractMethods = mutable.Buffer.empty[LinkedMember[MethodDef]]
    val exportedMembers = mutable.Buffer.empty[LinkedMember[Tree]]
    val classExports = mutable.Buffer.empty[Tree]

    def linkedMethod(m: MethodDef) = {
      val info = memberInfoByStaticAndName((m.static, m.name.encodedName))
      val version = m.hash.map(Hashers.hashAsVersion(_, considerPositions))
      new LinkedMember(info, m, version)
    }

    def linkedProperty(p: PropertyDef) = {
      val info = memberInfoByStaticAndName((p.static, p.name.encodedName))
      new LinkedMember(info, p, None)
    }

    def linkedSyntheticMethod(m: MethodDef) = {
      val info = Infos.generateMethodInfo(m)
      val version = m.hash.map(Hashers.hashAsVersion(_, considerPositions))
      new LinkedMember(info, m, version)
    }

    classDef.defs.foreach {
      // Static methods
      case m: MethodDef if m.static =>
        if (analyzerInfo.staticMethodInfos(m.name.encodedName).isReachable) {
          if (m.name.isInstanceOf[Ident])
            staticMethods += linkedMethod(m)
          else
            exportedMembers += linkedMethod(m)
        }

      // Fields
      case field @ FieldDef(_, _, _, _) =>
        if (analyzerInfo.isAnySubclassInstantiated)
          fields += field

      // Normal methods
      case m: MethodDef =>
        if (analyzerInfo.methodInfos(m.name.encodedName).isReachable) {
          if (m.name.isInstanceOf[Ident]) {
            if (m.body.isDefined)
              memberMethods += linkedMethod(m)
            else
              abstractMethods += linkedMethod(m)
          } else {
            exportedMembers += linkedMethod(m)
          }
        }

      case m: PropertyDef =>
        if (analyzerInfo.isAnySubclassInstantiated)
          exportedMembers += linkedProperty(m)

      case e: ConstructorExportDef =>
        classExports += e

      case e: JSClassExportDef =>
        classExports += e

      case e: ModuleExportDef =>
        classExports += e

      case e: TopLevelModuleExportDef =>
        classExports += e

      case e: TopLevelMethodExportDef =>
        classExports += e

      case e: TopLevelFieldExportDef =>
        classExports += e

      case tree =>
        throw new IllegalArgumentException(
            s"Illegal tree in ClassDef of class ${tree.getClass}")
    }

    // Synthetic members
    for {
      m <- analyzerInfo.methodInfos.valuesIterator
      if m.isReachable
    } {
      m.syntheticKind match {
        case MethodSyntheticKind.None =>
          // nothing to do

        case MethodSyntheticKind.InheritedConstructor =>
          val syntheticMDef = synthesizeInheritedConstructor(
              analyzerInfo, m, getTree, analysis)(classDef.pos)
          memberMethods += linkedSyntheticMethod(syntheticMDef)

        case MethodSyntheticKind.ReflectiveProxy(targetName) =>
          val syntheticMDef = synthesizeReflectiveProxy(
              analyzerInfo, m, targetName, getTree, analysis)
          memberMethods += linkedSyntheticMethod(syntheticMDef)

        case MethodSyntheticKind.DefaultBridge(targetInterface) =>
          val syntheticMDef = synthesizeDefaultBridge(
              analyzerInfo, m, targetInterface, getTree, analysis)
          memberMethods += linkedSyntheticMethod(syntheticMDef)
      }
    }

    val classExportInfo =
      memberInfoByStaticAndName.get((false, Definitions.ClassExportsName))

    val kind =
      if (analyzerInfo.isModuleAccessed) classDef.kind
      else classDef.kind.withoutModuleAccessor

    val ancestors = analyzerInfo.ancestors.map(_.encodedName)

    new LinkedClass(
        classDef.name,
        kind,
        classDef.superClass,
        classDef.interfaces,
        classDef.jsNativeLoadSpec,
        fields.toList,
        staticMethods.toList,
        memberMethods.toList,
        abstractMethods.toList,
        exportedMembers.toList,
        classExports.toList,
        classExportInfo,
        classDef.optimizerHints,
        classDef.pos,
        ancestors.toList,
        hasInstances = analyzerInfo.isAnySubclassInstantiated,
        hasInstanceTests = analyzerInfo.areInstanceTestsUsed,
        hasRuntimeTypeInfo = analyzerInfo.isDataAccessed,
        version)
  }

  private def synthesizeInheritedConstructor(
      classInfo: Analysis.ClassInfo, methodInfo: Analysis.MethodInfo,
      getTree: TreeProvider, analysis: Analysis)(
      implicit pos: Position): MethodDef = {
    val encodedName = methodInfo.encodedName

    val inheritedMDef = findInheritedMethodDef(analysis, classInfo.superClass,
        encodedName, getTree, _.syntheticKind == MethodSyntheticKind.None)

    val origName = inheritedMDef.name.asInstanceOf[Ident].originalName
    val ctorIdent = Ident(encodedName, origName)
    val params = inheritedMDef.args.map(_.copy()) // for the new pos
    val currentClassType = ClassType(classInfo.encodedName)
    val superClassType = ClassType(classInfo.superClass.encodedName)
    MethodDef(static = false, ctorIdent,
        params, NoType,
        Some(ApplyStatically(This()(currentClassType),
            superClassType, ctorIdent, params.map(_.ref))(NoType)))(
        OptimizerHints.empty,
        inheritedMDef.hash) // over-approximation
  }

  private def synthesizeReflectiveProxy(
      classInfo: Analysis.ClassInfo, methodInfo: Analysis.MethodInfo,
      targetName: String, getTree: TreeProvider,
      analysis: Analysis): MethodDef = {
    val encodedName = methodInfo.encodedName

    val targetMDef = findInheritedMethodDef(analysis, classInfo, targetName,
        getTree)

    implicit val pos = targetMDef.pos

    val targetIdent = targetMDef.name.asInstanceOf[Ident].copy() // for the new pos
    val proxyIdent = Ident(encodedName, None)
    val params = targetMDef.args.map(_.copy()) // for the new pos
    val currentClassType = ClassType(classInfo.encodedName)

    val call = Apply(This()(currentClassType),
        targetIdent, params.map(_.ref))(targetMDef.resultType)

    val body = if (targetName.endsWith("__C")) {
      // A Char needs to be boxed
      New(ClassType(Definitions.BoxedCharacterClass),
          Ident("init___C"), List(call))
    } else if (targetName.endsWith("__V")) {
      // Materialize an `undefined` result for void methods
      Block(call, Undefined())
    } else {
      call
    }

    MethodDef(static = false, proxyIdent, params, AnyType, Some(body))(
        OptimizerHints.empty, targetMDef.hash)
  }

  private def synthesizeDefaultBridge(
      classInfo: Analysis.ClassInfo, methodInfo: Analysis.MethodInfo,
      targetInterface: String,
      getTree: TreeProvider, analysis: Analysis): MethodDef = {
    val encodedName = methodInfo.encodedName

    val targetInterfaceInfo = analysis.classInfos(targetInterface)
    val targetMDef = findMethodDef(targetInterfaceInfo, encodedName, getTree)

    implicit val pos = targetMDef.pos

    val targetIdent = targetMDef.name.asInstanceOf[Ident].copy() // for the new pos
    val bridgeIdent = targetIdent
    val params = targetMDef.args.map(_.copy()) // for the new pos
    val currentClassType = ClassType(classInfo.encodedName)

    val body = ApplyStatically(
        This()(currentClassType), ClassType(targetInterface), targetIdent,
        params.map(_.ref))(targetMDef.resultType)

    MethodDef(static = false, bridgeIdent, params, targetMDef.resultType, Some(body))(
        OptimizerHints.empty, targetMDef.hash)
  }

  private def findInheritedMethodDef(analysis: Analysis,
      classInfo: Analysis.ClassInfo, methodName: String, getTree: TreeProvider,
      p: Analysis.MethodInfo => Boolean = _ => true): MethodDef = {
    @tailrec
    def loop(ancestorInfo: Analysis.ClassInfo): MethodDef = {
      assert(ancestorInfo != null,
          s"Could not find $methodName anywhere in ${classInfo.encodedName}")

      val inherited = ancestorInfo.methodInfos.get(methodName)
      inherited.find(p) match {
        case Some(m) =>
          m.syntheticKind match {
            case MethodSyntheticKind.None =>
              findMethodDef(ancestorInfo, methodName, getTree)

            case MethodSyntheticKind.DefaultBridge(targetInterface) =>
              val targetInterfaceInfo = analysis.classInfos(targetInterface)
              findMethodDef(targetInterfaceInfo, methodName, getTree)

            case MethodSyntheticKind.InheritedConstructor |
                MethodSyntheticKind.ReflectiveProxy(_) =>
              throw new AssertionError(
                  s"Cannot recursively follow $ancestorInfo.$methodName of " +
                  s"kind ${m.syntheticKind}")
          }

        case None =>
          loop(ancestorInfo.superClass)
      }
    }

    loop(classInfo)
  }

  private def findMethodDef(classInfo: Analysis.ClassInfo,
      methodName: String, getTree: TreeProvider): MethodDef = {
    val (classDef, _) = getTree(classInfo.encodedName)
    classDef.defs.collectFirst {
      case mDef: MethodDef
          if !mDef.static && mDef.name.encodedName == methodName => mDef
    }.getOrElse {
      throw new AssertionError(
          s"Cannot find $methodName in ${classInfo.encodedName}")
    }
  }

  private def checkConflictingExports(unit: LinkingUnit, logger: Logger,
      bypassLinkingErrors: Boolean): Unit = {
    val namesAndClasses = for {
      classDef <- unit.classDefs
      name <- classDef.topLevelExportNames
    } yield {
      name -> classDef
    }

    val level = if (bypassLinkingErrors) Level.Warn else Level.Error
    val errors = for {
      (name, namesAndClasses) <- namesAndClasses.groupBy(_._1)
      if namesAndClasses.size > 1
    } yield {
      logger.log(level, s"Conflicting top-level exports to $name from the " +
            "following classes:")
      for ((_, linkedClass) <- namesAndClasses) {
        logger.log(level, s"- ${linkedClass.fullName}")
      }

      ()
    }

    if (errors.nonEmpty && !bypassLinkingErrors)
      throw new LinkingException("There were conflicting exports.")
  }
}
