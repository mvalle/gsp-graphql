// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle

import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

import cats.Monoid
import cats.data.{ Chain, Ior, IorT, NonEmptyChain }
import cats.implicits._
import fs2.Stream
import io.circe.Json
import io.circe.syntax._

import Cursor.{Context, Env}
import Query._
import QueryInterpreter.{ mkErrorResult, ProtoJson }

class QueryInterpreter[F[_]](mapping: Mapping[F]) {

  /** Interpret `query` with expected type `rootTpe`.
   *
   *  The query is fully interpreted, including deferred or staged
   *  components.
   *
   *  The resulting Json value should include standard GraphQL error
   *  information in the case of failure.
   */
  def run(query: Query, rootTpe: Type, env: Env): Stream[F,Json] =
    runRoot(query, rootTpe, env).map(QueryInterpreter.mkResponse)

  /** Interpret `query` with expected type `rootTpe`.
   *
   *  The query is fully interpreted, including deferred or staged
   *  components.
   *
   *  Errors are accumulated on the `Left` of the result.
   */
  def runRoot(query: Query, rootTpe: Type, env: Env): Stream[F,Result[Json]] = {
    val rootQueries =
      query match {
        case Group(queries) => queries
        case query => List(query)
      }

    val introQueries = rootQueries.collect { case i: Introspect => i }
    val introResults =
      introQueries.map {
        case Introspect(schema, query) =>
          val interp = Introspection.interpreter(schema)
          interp.runRootValue(query, Introspection.schema.queryType, env)
      } .flatMap(_.compile.toList) // this is Stream[Id, *] so we can toList it

    val nonIntroQueries = rootQueries.filter { case _: Introspect => false ; case _ => true }
    val nonIntroResults = runRootValues(nonIntroQueries.map(q => (q, rootTpe, env)))

    val mergedResults: Stream[F,Result[ProtoJson]] =
      nonIntroResults.map {
        case (nonIntroErrors, nonIntroValues) =>

          val mergedErrors = introResults.foldLeft(nonIntroErrors) {
            case (acc, res) => res.left match {
              case Some(errs) => acc ++ errs.toChain
              case None => acc
            }
          }

          @tailrec
          def merge(qs: List[Query], is: List[Result[ProtoJson]], nis: List[ProtoJson], acc: List[ProtoJson]): List[ProtoJson] =
            ((qs, is, nis): @unchecked) match {
              case (Nil, _, _) => acc
              case ((_: Introspect) :: qs, i :: is, nis) =>
                val acc0 = i.right match {
                  case Some(r) => r :: acc
                  case None => acc
                }
                merge(qs, is, nis, acc0)
              case (_ :: qs, is, ni :: nis) =>
                merge(qs, is, nis, ni :: acc)
            }

          val mergedValues = ProtoJson.mergeObjects(merge(rootQueries, introResults, nonIntroValues, Nil).reverse)

          NonEmptyChain.fromChain(mergedErrors) match {
            case Some(errs) => Ior.Both(errs, mergedValues)
            case None => Ior.Right(mergedValues)
          }
      }
    (for {
      pvalue <- IorT(mergedResults)
      value  <- IorT(QueryInterpreter.complete[F](pvalue))
    } yield value).value
  }

  /** Interpret `query` with expected type `rootTpe`.
   *
   *  At most one stage will be run and the result may contain deferred
   *  components.
   *
   *  Errors are accumulated on the `Left` of the result.
   */
  def runRootValue0(query: Query, context: Context, env: Env): Stream[F,Result[ProtoJson]] =
    query match {
      case Environment(childEnv: Env, child: Query) =>
        runRootValue0(child, context, env.add(childEnv))

      case PossiblyRenamedSelect(Select(fieldName, _, child), resultName) =>
        (for {
          fieldTpe <- IorT.fromOption[Stream[F,*]](context.tpe.field(fieldName), QueryInterpreter.mkOneError(s"Root type ${context.tpe} has no field '$fieldName'"))
          qc       <- IorT(mapping.rootCursor(context, fieldName, Some(resultName), child, env))
          value    <- IorT(runValue(Wrap(resultName, qc._1), fieldTpe, qc._2).pure[Stream[F,*]])
        } yield value).value

      case Wrap(_, Component(mapping, _, child)) =>
        mapping.asInstanceOf[Mapping[F]].interpreter.runRootValue(child, context.tpe, env)

      case _ =>
        mkErrorResult(s"Bad root query '${query.render}' in QueryInterpreter").pure[Stream[F,*]]
    }

  def runRootValue(query: Query, rootTpe: Type, env: Env): Stream[F, Result[ProtoJson]] = runRootValue0(query, Context(rootTpe), env)

  /** Interpret multiple queries with respect to their expected types.
   *
   *  Each query is interpreted with respect to the expected type it is
   *  paired with. The result list is aligned with the argument list
   *  query list. For each query at most one stage will be run and the
   *  corresponding result may contain deferred components.
   *
   *  Errors are aggregated across all the argument queries and are
   *  accumulated on the `Left` of the result.
   *
   *  This method is typically called at the end of a stage to evaluate
   *  deferred subqueries in the result of that stage. These will be
   *  grouped by and passed jointly to the responsible interpreter in
   *  the next stage using this method. Interpreters which are able
   *  to benefit from combining queries may do so by overriding this
   *  method to implement their specific combinging logic.
   */
  def runRootValues(queries: List[(Query, Type, Env)]): Stream[F, (Chain[Problem], List[ProtoJson])] =
    queries.traverse((runRootValue _).tupled).map { rs =>
      (rs.foldLeft((Chain.empty[Problem], List.empty[ProtoJson])) {
        case ((errors, elems), elem) =>
          elem match {
            case Ior.Left(errs) => (errs.toChain ++ errors, ProtoJson.fromJson(Json.Null) :: elems)
            case Ior.Right(elem) => (errors, elem :: elems)
            case Ior.Both(errs, elem) => (errs.toChain ++ errors, elem :: elems)
          }
      }).fmap(_.reverse)
    }

  def cursorCompatible(tpe: Type, cursorTpe: Type): Boolean = {
    def strip(tpe: Type): Type =
      tpe.dealias match {
        case NullableType(tpe) => strip(tpe)
        case ListType(tpe) => strip(tpe)
        case _ => tpe
      }

    (strip(tpe).isLeaf && strip(cursorTpe).isLeaf) ||
    (strip(tpe) nominal_=:= strip(cursorTpe))
  }

  /**
   * Interpret `query` against `cursor`, yielding a collection of fields.
   *
   * If the query is valid, the field subqueries will all be valid fields
   * of the enclosing type `tpe` and the resulting fields may be used to
   * build a Json object of type `tpe`. If the query is invalid errors
   * will be returned on the left hand side of the result.
   */
  def runFields(query: Query, tpe: Type, cursor: Cursor): Result[List[(String, ProtoJson)]] =
    if (!cursorCompatible(tpe, cursor.tpe))
      mkErrorResult(s"Mismatched query and cursor type in runFields: $tpe ${cursor.tpe}")
    else {
      (query, tpe.dealias) match {
        case (Narrow(tp1, child), _) =>
          if (!cursor.narrowsTo(tp1)) Nil.rightIor
          else
            for {
              c      <- cursor.narrow(tp1)
              fields <- runFields(child, tp1, c)
            } yield fields

        case (Introspect(schema, PossiblyRenamedSelect(Select("__typename", Nil, Empty), resultName)), tpe: NamedType) =>
          (tpe match {
            case o: ObjectType => Some(o.name)
            case i: InterfaceType =>
              (schema.types.collectFirst {
                case o: ObjectType if o <:< i && cursor.narrowsTo(schema.ref(o.name)) => o.name
              })
            case u: UnionType =>
              (u.members.map(_.dealias).collectFirst {
                case nt: NamedType if cursor.narrowsTo(schema.ref(nt.name)) => nt.name
              })
            case _ => None
          }) match {
            case Some(name) =>
              List((resultName, ProtoJson.fromJson(Json.fromString(name)))).rightIor
            case None =>
              mkErrorResult(s"'__typename' cannot be applied to non-selectable type '$tpe'")
          }

        case (PossiblyRenamedSelect(sel, resultName), NullableType(tpe)) =>
          cursor.asNullable.sequence.map { rc =>
            for {
              c      <- rc
              fields <- runFields(sel, tpe, c)
            } yield fields
          }.getOrElse(List((resultName, ProtoJson.fromJson(Json.Null))).rightIor)

        case (PossiblyRenamedSelect(Select(fieldName, _, child), resultName), tpe) =>
          for {
            fieldTpe <- tpe.field(fieldName).toRightIor(QueryInterpreter.mkOneError(s"Type $tpe has no field '$fieldName'"))
            c        <- cursor.field(fieldName, Some(resultName))
            value    <- runValue(child, fieldTpe, c)
          } yield List((resultName, value))

        case (Rename(resultName, Wrap(_, child)), tpe) =>
          runFields(Wrap(resultName, child), tpe, cursor)

        case (Wrap(fieldName, child), tpe) =>
          for {
            value <- runValue(child, tpe, cursor)
          } yield List((fieldName, value))

        case (Rename(resultName, Count(_, child)), tpe) =>
          runFields(Count(resultName, child), tpe, cursor)

        case (Count(fieldName, Select(countName, _, _)), _) =>
          cursor.field(countName, None).flatMap { c0 =>
            if (c0.isNullable)
              c0.asNullable.flatMap {
                case None => 0.rightIor
                case Some(c1) =>
                  if (c1.isList) c1.asList(Iterator).map(_.size)
                  else 1.rightIor
              }
            else if (c0.isList) c0.asList(Iterator).map(_.size)
            else 1.rightIor
          }.map { value => List((fieldName, ProtoJson.fromJson(Json.fromInt(value)))) }

        case (Group(siblings), _) =>
          siblings.flatTraverse(query => runFields(query, tpe, cursor))

        case (Environment(childEnv: Env, child: Query), tpe) =>
          runFields(child, tpe, cursor.withEnv(childEnv))

        case _ =>
          mkErrorResult(s"runFields failed: { ${query.render} } $tpe")
      }
    }

  def runList(query: Query, tpe: Type, cursors: Iterator[Cursor], unique: Boolean, nullable: Boolean): Result[ProtoJson] = {
    val (child, ic) =
      query match {
        case FilterOrderByOffsetLimit(pred, selections, offset, limit, child) =>
          val filtered =
            pred.map { p =>
              cursors.filter { c =>
                p(c) match {
                  case left@Ior.Left(_) => return left
                  case Ior.Right(c) => c
                  case Ior.Both(_, c) => c
                }
              }
            }.getOrElse(cursors)
          val sorted = selections.map(OrderSelections(_).order(filtered.toSeq).iterator).getOrElse(filtered)
          val sliced = (offset, limit) match {
            case (None, None) => sorted
            case (Some(off), None) => sorted.drop(off)
            case (None, Some(lim)) => sorted.take(lim)
            case (Some(off), Some(lim)) => sorted.slice(off, off+lim)
          }
          (child, sliced)
        case other => (other, cursors)
      }

    val builder = Vector.newBuilder[ProtoJson]
    var problems = Chain.empty[Problem]
    builder.sizeHint(ic.knownSize)
    while(ic.hasNext) {
      val c = ic.next()
      if (!cursorCompatible(tpe, c.tpe))
        return mkErrorResult(s"Mismatched query and cursor type in runList: $tpe ${cursors.map(_.tpe)}")

      runValue(child, tpe, c) match {
        case left@Ior.Left(_) => return left
        case Ior.Right(v) => builder.addOne(v)
        case Ior.Both(ps, v) =>
          builder.addOne(v)
          problems = problems.concat(ps.toChain)
      }
    }

    def mkResult(j: ProtoJson): Result[ProtoJson] =
      NonEmptyChain.fromChain(problems).map(neps => Ior.Both(neps, j)).getOrElse(j.rightIor)

    if (!unique) mkResult(ProtoJson.fromValues(builder.result()))
    else {
      val size = builder.knownSize
      if (size == 1) mkResult(builder.result()(0))
      else if (size == 0) {
        if(nullable) mkResult(ProtoJson.fromJson(Json.Null))
        else mkErrorResult(s"No match")
      } else mkErrorResult(s"Multiple matches")
    }
  }

  /**
   * Interpret `query` against `cursor` with expected type `tpe`.
   *
   * If the query is invalid errors will be returned on the left hand side
   * of the result.
   */
  def runValue(query: Query, tpe: Type, cursor: Cursor): Result[ProtoJson] =
    if (!cursorCompatible(tpe, cursor.tpe))
      mkErrorResult(s"Mismatched query and cursor type in runValue: $tpe ${cursor.tpe}")
    else {
      def mkResult[T](ot: Option[T]): Result[T] = ot match {
        case Some(t) => t.rightIor
        case None => mkErrorResult(s"Join continuation has unexpected shape")
      }

      (query, tpe.dealias) match {
        case (Environment(childEnv: Env, child: Query), tpe) =>
          runValue(child, tpe, cursor.withEnv(childEnv))

        case (Wrap(_, Component(_, _, _)), ListType(tpe)) =>
          // Keep the wrapper with the component when going under the list
          cursor.asList(Iterator).map { ic =>
            val builder = Vector.newBuilder[ProtoJson]
            builder.sizeHint(ic.knownSize)
            while(ic.hasNext) {
              val c = ic.next()
              runValue(query, tpe, c) match {
                case Ior.Right(v) => builder.addOne(v)
                case left => return left
              }
            }
            ProtoJson.fromValues(builder.result())
          }

        case (Wrap(_, Defer(_, _, _)), _) if cursor.isNull =>
          ProtoJson.fromJson(Json.Null).rightIor

        case (Wrap(fieldName, child), _) =>
          for {
            pvalue <- runValue(child, tpe, cursor)
          } yield ProtoJson.fromFields(List((fieldName, pvalue)))

        case (Component(mapping, join, PossiblyRenamedSelect(child, resultName)), tpe) =>
          val interpreter = mapping.interpreter
          join(cursor, child).flatMap {
            case Group(conts) =>
              conts.traverse { case cont =>
                for {
                  componentName <- mkResult(rootName(cont))
                  itemTpe       <- tpe.field(child.name).flatMap(_.item).toRightIor(QueryInterpreter.mkOneError(s"Type $tpe has no list field '${child.name}'"))
                } yield
                  ProtoJson.select(
                    ProtoJson.staged(interpreter, cont, JoinType(componentName, itemTpe), cursor.fullEnv),
                    componentName
                  )
              }.map(ProtoJson.fromValues)

            case cont =>
              for {
                componentName <- mkResult(rootName(cont))
                renamedCont   <- mkResult(renameRoot(cont, resultName))
                fieldTpe      <- tpe.field(child.name).toRightIor(QueryInterpreter.mkOneError(s"Type $tpe has no field '${child.name}'"))
              } yield ProtoJson.staged(interpreter, renamedCont, JoinType(componentName, fieldTpe), cursor.fullEnv)
          }

        case (Defer(join, child, rootTpe), _) =>
          def stage(cursor: Cursor) =
            for {
              cont <- join(cursor, child)
            } yield ProtoJson.staged(this, cont, rootTpe, cursor.fullEnv)

          if (cursor.isNullable)
            cursor.asNullable match {
              case Ior.Right(Some(c)) => stage(c)
              case _ => ProtoJson.fromJson(Json.Null).rightIor
            }
          else stage(cursor)

        case (Unique(child), _) =>
          cursor.preunique.flatMap(_.asList(Iterator).flatMap { cursors =>
            runList(child, tpe.nonNull, cursors, true, tpe.isNullable)
          })

        case (_, ListType(tpe)) =>
          cursor.asList(Iterator).flatMap { cursors =>
            runList(query, tpe, cursors, false, false)
          }

        case (_, NullableType(tpe)) =>
          cursor.asNullable.sequence.map { rc =>
            for {
              c     <- rc
              value <- runValue(query, tpe, c)
            } yield value
          }.getOrElse(ProtoJson.fromJson(Json.Null).rightIor)

        case (_, (_: ScalarType) | (_: EnumType)) =>
          cursor.asLeaf.map(ProtoJson.fromJson)

        case (_, (_: ObjectType) | (_: InterfaceType) | (_: UnionType)) =>
          runFields(query, tpe, cursor).map(ProtoJson.fromFields)

        case _ =>
          mkErrorResult(s"Stuck at type $tpe for ${query.render}")
      }
    }
}

object QueryInterpreter {
  /**
   * Opaque type of partially constructed query results.
   *
   * Values may be fully expanded Json values, objects or arrays which not
   * yet fully evaluated subtrees, or subqueries which are deferred to the
   * next stage or another component of a composite interpreter.
   */
  type ProtoJson <: AnyRef

  object ProtoJson {
    private[QueryInterpreter] sealed trait DeferredJson
    // A result which is deferred to the next stage or component of this interpreter.
    private[QueryInterpreter] case class StagedJson[F[_]](interpreter: QueryInterpreter[F], query: Query, rootTpe: Type, env: Env) extends DeferredJson
    // A partially constructed object which has at least one deferred subtree.
    private[QueryInterpreter] case class ProtoObject(fields: Seq[(String, ProtoJson)])
    // A partially constructed array which has at least one deferred element.
    private[QueryInterpreter] case class ProtoArray(elems: Seq[ProtoJson])
    // A result which will yield a selection from its child
    private[QueryInterpreter] case class ProtoSelect(elem: ProtoJson, fieldName: String)

    /**
     * Delegate `query` to the interpreter `interpreter`. When evaluated by
     * that interpreter the query will have expected type `rootTpe`.
     */
    def staged[F[_]](interpreter: QueryInterpreter[F], query: Query, rootTpe: Type, env: Env): ProtoJson =
      wrap(StagedJson(interpreter, query, rootTpe, env))

    def fromJson(value: Json): ProtoJson = wrap(value)

    /**
     * Combine possibly partial fields to create a possibly partial object.
     *
     * If all fields are complete then they will be combined as a complete
     * Json object.
     */
    def fromFields(fields: Seq[(String, ProtoJson)]): ProtoJson =
      if(fields.forall(_._2.isInstanceOf[Json]))
        wrap(Json.fromFields(fields.asInstanceOf[Seq[(String, Json)]]))
      else
        wrap(ProtoObject(fields))

    /**
     * Combine possibly partial values to create a possibly partial array.
     *
     * If all values are complete then they will be combined as a complete
     * Json array.
     */
    def fromValues(elems: Seq[ProtoJson]): ProtoJson =
      if(elems.forall(_.isInstanceOf[Json]))
        wrap(Json.fromValues(elems.asInstanceOf[Seq[Json]]))
      else
        wrap(ProtoArray(elems))

    /**
     * Select a value from a possibly partial object.
     *
     * If the object is complete the selection will be a complete
     * Json value.
     */
    def select(elem: ProtoJson, fieldName: String): ProtoJson =
      elem match {
        case j: Json =>
          wrap(j.asObject.flatMap(_(fieldName)).getOrElse(Json.Null))
        case _ =>
          wrap(ProtoSelect(elem, fieldName))
      }

    /**
     * Test whether the argument contains any deferred subtrees
     *
     * Yields `true` if the argument contains any component or staged
     * subtrees, false otherwise.
     */
    def isDeferred(p: ProtoJson): Boolean =
      p.isInstanceOf[DeferredJson]

    def mergeObjects(elems: List[ProtoJson]): ProtoJson = {
      def loop(elems: List[ProtoJson], acc: List[(String, ProtoJson)]): List[(String, ProtoJson)] = elems match {
        case Nil                       => acc
        case (j: Json) :: tl =>
          j.asObject match {
            case Some(obj)             => loop(tl, acc ++ obj.keys.zip(obj.values.map(fromJson)))
            case None                  => loop(tl, acc)
          }
        case ProtoObject(fields) :: tl => loop(tl, acc ++ fields)
        case _ :: tl                   => loop(tl, acc)
      }

      elems match {
        case Nil        => wrap(Json.Null)
        case hd :: Nil  => hd
        case _          =>
          loop(elems, Nil) match {
            case Nil    => wrap(Json.Null)
            case fields => fromFields(fields)
          }
      }
    }

    private def wrap(j: AnyRef): ProtoJson = j.asInstanceOf[ProtoJson]
  }

  import ProtoJson._

  /**
   * Complete a possibly partial result.
   *
   * Completes a single possibly partial result as described for
   * `completeAll`.
   */
  def complete[F[_]](pj: ProtoJson): Stream[F,Result[Json]] =
    pj match {
      case j: Json => j.rightIor.pure[Stream[F, *]]
      case _ =>
        completeAll[F](List(pj)).map {
          case (errors, List(value)) =>
            NonEmptyChain.fromChain(errors) match {
              case Some(errors) => Ior.Both(errors, value)
              case None => value.rightIor
            }
          case _ =>
            mkErrorResult("completeAll yielded impossible result")
        }
    }

  /** Complete a collection of possibly deferred results.
   *
   *  Each result is completed by locating any subtrees which have been
   *  deferred or delegated to some other component interpreter in an
   *  overall composite interpreter. Deferred subtrees are gathered,
   *  grouped by their associated interpreter and then evaluated in
   *  batches. The results of these batch evaluations are then
   *  completed in a subsequent stage recursively until the results are
   *  fully evaluated or yield errors.
   *
   *  Complete results are substituted back into the corresponding
   *  enclosing Json.
   *
   *  Errors are aggregated across all the results and are accumulated
   *  on the `Left` of the result.
   */
  def completeAll[F[_]](pjs: List[ProtoJson]): Stream[F, (Chain[Problem], List[Json])] = {
    def gatherDeferred(pj: ProtoJson): List[DeferredJson] = {
      @tailrec
      def loop(pending: Chain[ProtoJson], acc: List[DeferredJson]): List[DeferredJson] =
        pending.uncons match {
          case None => acc
          case Some((hd, tl)) => hd match {
            case _: Json              => loop(tl, acc)
            case d: DeferredJson      => loop(tl, d :: acc)
            case ProtoObject(fields)  => loop(Chain.fromSeq(fields.map(_._2)) ++ tl, acc)
            case ProtoArray(elems)    => loop(Chain.fromSeq(elems) ++ tl, acc)
            case ProtoSelect(elem, _) => loop(elem +: tl, acc)
            case _                    => sys.error("impossible")
          }
        }

      pj match {
        case _: Json => Nil
        case _ => loop(Chain.one(pj), Nil)
      }
    }

    def scatterResults(pj: ProtoJson, subst: mutable.Map[DeferredJson, Json]): Json = {
      def loop(pj: ProtoJson): Json =
        pj match {
          case p: Json         => p
          case d: DeferredJson => subst(d)
          case ProtoObject(fields) =>
            val newFields: Seq[(String, Json)] =
              fields.flatMap { case (label, pvalue) =>
                val value = loop(pvalue)
                if (isDeferred(pvalue) && value.isObject) {
                  value.asObject.get.toList match {
                    case List((_, value)) => List((label, value))
                    case other => other
                  }
                }
                else List((label, value))
              }
            Json.fromFields(newFields)

          case ProtoArray(elems) =>
            val elems0 = elems.map(loop)
            Json.fromValues(elems0)
          case ProtoSelect(elem, fieldName) =>
            loop(elem).asObject.flatMap(_(fieldName)).getOrElse(Json.Null)

          case _ => sys.error("impossible")
        }

      loop(pj)
    }

    val collected = pjs.flatMap(gatherDeferred)

    val (good, bad, errors0) =
      collected.foldLeft((List.empty[(DeferredJson, QueryInterpreter[F], (Query, Type, Env))], List.empty[DeferredJson], Chain.empty[Problem])) {
        case ((good, bad, errors), d@StagedJson(interpreter, query, rootTpe, env)) =>
          ((d, interpreter.asInstanceOf[QueryInterpreter[F]], (query, rootTpe, env)) :: good, bad, errors)
      }

    val grouped = good.groupMap(_._2)(e => (e._1, e._3)).toList

    val staged =
      (grouped.traverse {
        case (i, dq) =>
          val (ds, qs) = dq.unzip
          for {
            pnext <- i.runRootValues(qs)
            next  <- completeAll[F](pnext._2)
          } yield (pnext._1 ++ next._1, ds.zip(next._2))
      }).map(Monoid.combineAll(_))

    staged.map {
      case (errors1, assoc) =>
        val subst = {
          val m = new java.util.IdentityHashMap[DeferredJson, Json]
          bad.foreach(dj => m.put(dj, Json.Null))
          assoc.foreach { case (d, j) => m.put(d, j) }
          m.asScala
        }
        val values = pjs.map(pj => scatterResults(pj, subst))
        (errors0 ++ errors1, values)
    }
  }

  /**
   * Construct a GraphQL response from the possibly absent result `data`
   * and a collection of errors.
   */
  def mkResponse(data: Option[Json], errors: List[Problem]): Json = {
    val dataField = data.map { value => ("data", value) }.toList
    val fields =
      (dataField, errors) match {
        case (Nil, Nil)   => List(("errors", Json.fromValues(List(mkError("Invalid query").asJson))))
        case (data, Nil)  => data
        case (data, errs) => ("errors", errs.asJson) :: data
      }
    Json.fromFields(fields)
  }

  /** Construct a GraphQL response from a `Result`. */
  def mkResponse(result: Result[Json]): Json =
    mkResponse(result.right, result.left.map(_.toList).getOrElse(Nil))

  /**
   *  Construct a GraphQL error response from a `Result`, ignoring any
   *  right hand side in `result`.
   */
  def mkInvalidResponse(result: Result[Operation]): Json =
    mkResponse(None, result.left.map(_.toList).getOrElse(Nil))

  /** Construct a GraphQL error object */
  def mkError(message: String, locations: List[(Int, Int)] = Nil, path: List[String] = Nil): Problem =
    Problem(message, locations, path)

  def mkOneError(message: String, locations: List[(Int, Int)] = Nil, path: List[String] = Nil): NonEmptyChain[Problem] =
    NonEmptyChain.one(mkError(message, locations, path))

  /** Construct a GraphQL error object as the left hand side of a `Result` */
  def mkErrorResult[T](message: String, locations: List[(Int, Int)] = Nil, path: List[String] = Nil): Result[T] =
    Ior.leftNec(mkError(message, locations, path))
}
