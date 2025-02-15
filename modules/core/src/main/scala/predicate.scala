// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import scala.annotation.tailrec
import scala.util.matching.Regex

import cats.Apply
import cats.data.Ior
import cats.kernel.{ Eq, Order }
import cats.implicits._

import QueryInterpreter.mkErrorResult

/**
 * A reified function over a `Cursor`.
 *
 * Query interpreters will typically need to introspect predicates (eg. in the doobie module
 * we need to be able to construct where clauses from predicates over fields/attributes), so
 * these cannot be arbitrary functions `Cursor => Boolean`.
 */
trait Term[T] extends Product with Serializable { // fun fact: making this covariant crashes Scala 3
  def apply(c: Cursor): Result[T]

  def children: List[Term[_]]

  def fold[Acc](acc: Acc)(f: (Acc, Term[_]) => Acc): Acc =
    children.foldLeft(f(acc, this)) { case (acc, child) => child.fold(acc)(f) }

  def exists(f: Term[_] => Boolean): Boolean =
    f(this) || children.exists(_.exists(f))

  def forall(f: Term[_] => Boolean): Boolean =
    f(this) && children.forall(_.forall(f))
}

object Term extends TermLow {
  implicit def path2Term[A](p: Path): Term[A] =
    p.asTerm[A]
}

trait TermLow {
  implicit def path2ListTerm[A](p: Path): Term[List[A]] =
    p.asTerm[List[A]]
}

/** A path starting from some root type. */
case class Path private (root: Type, path: List[String]) {

  def asTerm[A]: Term[A] =
    if (isList) PathTerm.ListPath(path)
    else PathTerm.UniquePath(path)

  lazy val isList: Boolean = root.pathIsList(path)
  lazy val tpe: Option[Type] = root.path(path)

  def prepend(prefix: List[String]): Path =
    copy(path = prefix ++ path)

  def /(elem: String): Path =
    copy(path = path :+ elem)

}

object Path {

  /** Construct an empty `Path` rooted at the given type. */
  def from(tpe: Type): Path =
    Path(tpe, Nil)

}

sealed trait PathTerm {
  def children: List[Term[_]] =  Nil
  def path: List[String]
}
object PathTerm {

  case class ListPath[A](path: List[String]) extends Term[A] with PathTerm {
    def apply(c: Cursor): Result[A] =
      c.flatListPath(path).map(_.map {
        case Predicate.ScalarFocus(f) => f
        case _ => sys.error("impossible")
      } .asInstanceOf[A])
  }

  case class UniquePath[A](path: List[String]) extends Term[A] with PathTerm {
    def apply(c: Cursor): Result[A] =
      c.listPath(path) match {
        case Ior.Right(List(Predicate.ScalarFocus(a: A @unchecked))) => a.rightIor
        case other => mkErrorResult(s"Expected exactly one element for path $path found $other")
      }
  }

}

trait Predicate extends Term[Boolean]

object Predicate {
  object ScalarFocus {
    def unapply(c: Cursor): Option[Any] =
      if (c.isLeaf) Some(c.focus)
      else if (c.isNullable && c.tpe.nonNull.isLeaf)
        c.asNullable match {
          case Ior.Right(Some(c)) => unapply(c).map(Some(_))
          case _ => Some(None)
        }
      else None
  }

  case object True extends Predicate {
    def apply(c: Cursor): Result[Boolean] = true.rightIor

    def children = Nil
  }

  case object False extends Predicate {
    def apply(c: Cursor): Result[Boolean] = false.rightIor

    def children = Nil
  }

  def and(props: List[Predicate]): Predicate = {
    @tailrec
    def loop(props: List[Predicate], acc: Predicate): Predicate =
      props match {
        case Nil => acc
        case False :: _ => False
        case True :: tl => loop(tl, acc)
        case hd :: tl if acc == True => loop(tl, hd)
        case hd :: tl => loop(tl, And(hd, acc))
      }
    loop(props, True)
  }

  def or(props: List[Predicate]): Predicate = {
    @tailrec
    def loop(props: List[Predicate], acc: Predicate): Predicate =
      props match {
        case Nil => acc
        case True :: _ => True
        case False :: tl => loop(tl, acc)
        case hd :: tl if acc == False => loop(tl, hd)
        case hd :: tl => loop(tl, Or(hd, acc))
      }
    loop(props, False)
  }

  case class Const[T](v: T) extends Term[T] {
    def apply(c: Cursor): Result[T] = v.rightIor
    def children = Nil
  }

  case class And(x: Predicate, y: Predicate) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_ && _)
    def children = List(x, y)
  }

  object And {
    def combineAll(preds: List[Predicate]): Predicate =
      preds match {
        case Nil => True
        case List(pred) => pred
        case hd :: tail => tail.foldRight(hd)((elem, acc) => And(elem, acc))
      }
  }

  case class Or(x: Predicate, y: Predicate) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_ || _)
    def children = List(x, y)
  }

  case class Not(x: Predicate) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = x(c).map(! _)
    def children = List(x)
  }

  case class Eql[T: Eq](x: Term[T], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_ === _)
    def eqInstance: Eq[T] = implicitly[Eq[T]]
    def children = List(x, y)
    def subst(x: Term[T], y: Term[T]): Eql[T] = copy(x = x, y = y)
  }

  case class NEql[T: Eq](x: Term[T], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_ =!= _)
    def children = List(x, y)
    def subst(x: Term[T], y: Term[T]): NEql[T] = copy(x = x, y = y)
  }

  case class Contains[T: Eq](x: Term[List[T]], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))((xs, y0) => xs.exists(_ === y0))
    def children = List(x, y)
    def subst(x: Term[List[T]], y: Term[T]): Contains[T] = copy(x = x, y = y)
  }

  case class Lt[T: Order](x: Term[T], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_.compare(_) < 0)
    def children = List(x, y)
    def subst(x: Term[T], y: Term[T]): Lt[T] = copy(x = x, y = y)
  }

  case class LtEql[T: Order](x: Term[T], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_.compare(_) <= 0)
    def children = List(x, y)
    def subst(x: Term[T], y: Term[T]): LtEql[T] = copy(x = x, y = y)
  }

  case class Gt[T: Order](x: Term[T], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_.compare(_) > 0)
    def children = List(x, y)
    def subst(x: Term[T], y: Term[T]): Gt[T] = copy(x = x, y = y)
  }

  case class GtEql[T: Order](x: Term[T], y: Term[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = Apply[Result].map2(x(c), y(c))(_.compare(_) >= 0)
    def children = List(x, y)
    def subst(x: Term[T], y: Term[T]): GtEql[T] = copy(x = x, y = y)
  }

  case class IsNull[T](x: Term[Option[T]], isNull: Boolean) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = x(c).map(_.isEmpty == isNull)
    def children = List(x)
  }

  case class In[T: Eq](x: Term[T], y: List[T]) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = x(c).map(y.contains_(_))
    def children = List(x)
    def subst(x: Term[T]): In[T] = copy(x = x)
  }

  object In {
    def fromEqls[T](eqls: List[Eql[T]]): Option[In[T]] = {
      val paths = eqls.map(_.x).distinct
      val consts = eqls.collect { case Eql(_, Const(c)) => c }
      if (eqls.map(_.x).distinct.sizeCompare(1) == 0 && consts.sizeCompare(eqls) == 0)
        Some(In(paths.head, consts)(eqls.head.eqInstance))
      else
        None
    }
  }

  case class AndB(x: Term[Int], y: Term[Int]) extends Term[Int] {
    def apply(c: Cursor): Result[Int] = Apply[Result].map2(x(c), y(c))(_ & _)
    def children = List(x, y)
  }

  case class OrB(x: Term[Int], y: Term[Int]) extends Term[Int] {
    def apply(c: Cursor): Result[Int] = Apply[Result].map2(x(c), y(c))(_ | _)
    def children = List(x, y)
  }

  case class XorB(x: Term[Int], y: Term[Int]) extends Term[Int] {
    def apply(c: Cursor): Result[Int] = Apply[Result].map2(x(c), y(c))(_ ^ _)
    def children = List(x, y)
  }

  case class NotB(x: Term[Int]) extends Term[Int] {
    def apply(c: Cursor): Result[Int] = x(c).map(~ _)
    def children = List(x)
  }

  case class Matches(x: Term[String], r: Regex) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = x(c).map(r.matches(_))
    def children = List(x)
  }

  case class StartsWith(x: Term[String], prefix: String) extends Predicate {
    def apply(c: Cursor): Result[Boolean] = x(c).map(_.startsWith(prefix))
    def children = List(x)
  }

  case class ToUpperCase(x: Term[String]) extends Term[String] {
    def apply(c: Cursor): Result[String] = x(c).map(_.toUpperCase)
    def children = List(x)
  }

  case class ToLowerCase(x: Term[String]) extends Term[String] {
    def apply(c: Cursor): Result[String] = x(c).map(_.toLowerCase)
    def children = List(x)
  }
}
