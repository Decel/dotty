package dotty.tools.dotc
package transform

import core._
import MegaPhase._
import Contexts._
import Symbols._
import Flags._
import StdNames._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.transform.init.Util.isMutable



/** This phase rewrites calls to `Array.apply` to a direct instantiation of the array in the bytecode.
 *
 *  Transforms `scala.Array.apply([....])` and `scala.Array.apply(..., [....])` into `[...]`
 */
class ArrayApply extends MiniPhase {
  import tpd._

  override def phaseName: String = ArrayApply.name

  override def description: String = ArrayApply.description

  private var transformListApplyLimit = 8

  private def reducingTransformListApply[A](depth: Int)(body: => A): A = {
      val saved = transformListApplyLimit
      transformListApplyLimit -= depth
      try body
      finally transformListApplyLimit = saved
    }

  override def transformApply(tree: tpd.Apply)(using Context): tpd.Tree =
    if isArrayModuleApply(tree.symbol) then
      tree.args match
        case StripAscription(Apply(wrapRefArrayMeth, (seqLit: tpd.JavaSeqLiteral) :: Nil)) :: ct :: Nil
            if defn.WrapArrayMethods().contains(wrapRefArrayMeth.symbol) && elideClassTag(ct) =>
          seqLit

        case elem0 :: StripAscription(Apply(wrapRefArrayMeth, (seqLit: tpd.JavaSeqLiteral) :: Nil)) :: Nil
            if defn.WrapArrayMethods().contains(wrapRefArrayMeth.symbol) =>
          tpd.JavaSeqLiteral(elem0 :: seqLit.elems, seqLit.elemtpt)

        case _ =>
          tree

    else if isSeqApply(tree) then
      println("Succsess")
      tree.args match
        // <List or Seq>(a, b, c) ~> new ::(a, new ::(b, new ::(c, Nil))) but only for reference types
        case StripAscription(Apply(wrapArrayMeth, List(StripAscription(rest: tpd.JavaSeqLiteral)))) :: Nil
          if defn.WrapArrayMethods().contains(wrapArrayMeth.symbol) &&
            rest.elems.lengthIs < transformListApplyLimit =>
          rest.elems.foldRight(tpd.ref(defn.NilModule)): (elem, acc) => 
            tpd.New(defn.ConsType, List(elem.ensureConforms(defn.ObjectType), acc))

        case _ =>
          tree

    else tree

  private def isArrayModuleApply(sym: Symbol)(using Context): Boolean =
    sym.name == nme.apply
    && (sym.owner == defn.ArrayModuleClass || (sym.owner == defn.IArrayModuleClass && !sym.is(Extension)))

  private def isListApply(tree: Tree)(using Context): Boolean =
    (tree.symbol == defn.ListModule_apply || tree.symbol.name == nme.apply) && scala.PartialFunction.cond(tree) {
      case Apply(Select(qual, name), rest) =>
        // println(s"qual: $qual")
        // println(s"name: $name")
        // println(s"rest: $rest")
        (qual.symbol == defn.ListModule) 
    }

  private def isSeqApply(tree: Tree)(using Context): Boolean = isListApply(tree) || {
    (tree.symbol == defn.Seq_apply) && (tree match {
      case Apply(Select(qual, name), rest) =>
        // println(s"qual: $qual")
        // println(s"name: $name")
        // println(s"rest: $rest")
        qual.symbol == defn.SeqFactoryClass
      case _ => false
    })
  }

  /** Only optimize when classtag if it is one of
   *  - `ClassTag.apply(classOf[XYZ])`
   *  - `ClassTag.apply(java.lang.XYZ.Type)` for boxed primitives `XYZ``
   *  - `ClassTag.XYZ` for primitive types
   */
  private def elideClassTag(ct: Tree)(using Context): Boolean = ct match {
    case Apply(_, rc :: Nil) if ct.symbol == defn.ClassTagModule_apply =>
      rc match {
        case _: Literal => true // ClassTag.apply(classOf[XYZ])
        case rc: RefTree if rc.name == nme.TYPE_ =>
          // ClassTag.apply(java.lang.XYZ.Type)
          defn.ScalaBoxedClasses().contains(rc.symbol.maybeOwner.companionClass)
        case _ => false
      }
    case Apply(ctm: RefTree, _) if ctm.symbol.maybeOwner.companionModule == defn.ClassTagModule =>
      // ClassTag.XYZ
      nme.ScalaValueNames.contains(ctm.name)
    case _ => false
  }

  object StripAscription {
    def unapply(tree: Tree)(using Context): Some[Tree] = tree match {
      case Typed(expr, _) => unapply(expr)
      case _ => Some(tree)
    }
  }
}

object ArrayApply:
  val name: String = "arrayApply"
  val description: String = "optimize `scala.Array.apply`"
