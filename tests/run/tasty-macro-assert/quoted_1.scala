import scala.quoted._

import scala.tasty.Universe

object Asserts {

  implicit class Ops[T](left: T) {
    def ===(right: T): Boolean = left == right
    def !==(right: T): Boolean = left != right
  }

  object Ops

  inline def macroAssert(cond: Boolean): Unit =
    ~impl('(cond))(Universe.compilationUniverse) // FIXME infer Universe.compilationUniverse within top level ~

  def impl(cond: Expr[Boolean])(implicit u: Universe): Expr[Unit] = {
    import u._
    import u.tasty._

    val tree = cond.toTasty

    def isOps(tpe: TypeOrBounds): Boolean = tpe match {
      case Type.SymRef(DefDef("Ops", _, _, _, _),
        pre @ Type.SymRef(ValDef("Asserts", _, _),
          Type.ThisType(Type.SymRef(PackageDef("<empty>", _), NoPrefix())))) =>
        val tpeShow = tpe.show
        val tpeShow1 = showExtractors.showTypeOrBounds(tpe)(u.context)
        val preShow = pre.show
        val preShow1 = showExtractors.showTypeOrBounds(pre)(u.context)
        assert(tpeShow == tpeShow1)
        assert(preShow == preShow1)
        println(tpeShow)
        println(preShow)
        true
      case _ => false
    }

    object OpsTree {
      def unapply(arg: Term): Option[Term] = arg match {
        case Term.Apply(Term.TypeApply(term, _), left :: Nil) if isOps(term.tpe) =>
          Some(left)
        case _ => None
      }
    }

    tree match {
      case Term.Apply(Term.Select(OpsTree(left), op, _), right :: Nil) =>
        op match {
          case "===" => '(assertEquals(~left.toExpr[Any], ~right.toExpr[Any]))
          case "!==" => '(assertNotEquals(~left.toExpr[Any], ~right.toExpr[Any]))
        }
      case _ =>
        '(assertTrue(~cond))
    }

  }

  def assertEquals[T](left: T, right: T): Unit = {
    if (left != right) {
      println(
        s"""Error left did not equal right:
           |  left  = $left
           |  right = $right""".stripMargin)
    }

  }

  def assertNotEquals[T](left: T, right: T): Unit = {
    if (left == right) {
      println(
        s"""Error left was equal to right:
           |  left  = $left
           |  right = $right""".stripMargin)
    }

  }

  def assertTrue(cond: Boolean): Unit = {
    if (!cond)
      println("Condition was false")
  }

}
