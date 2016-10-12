/*
 * Copyright 2014–2016 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.sparkcore.fs

import quasar.Predef._
import quasar.qscript.QScriptHelpers
import quasar.qscript._
import quasar.qscript.ReduceFuncs._
import quasar.qscript.MapFuncs._
import quasar.contrib.pathy._
import quasar.Data
import quasar.qscript._

import matryoshka.{Hole => _, _}
import org.apache.spark._
import org.apache.spark.rdd._
import org.specs2.scalaz.DisjunctionMatchers
import pathy.Path._
import scalaz._, Scalaz._, scalaz.concurrent.Task

class PlannerSpec extends quasar.Qspec with QScriptHelpers with DisjunctionMatchers {

  import Planner.SparkState

  sequential

  val equi = Planner.equiJoin[Fix]
  val sr = Planner.shiftedread[Fix]
  val qscore = Planner.qscriptCore[Fix]
  def compileCore: AlgebraM[SparkState, QScriptCore[Fix, ?], RDD[Data]] = qscore.plan(emptyFF)
  def compileJoin: AlgebraM[SparkState, EquiJoin[Fix, ?], RDD[Data]] = equi.plan(emptyFF)

  "Planner" should {
    "shiftedread" in {

      withSparkContext { sc =>
        val fromFile: (SparkContext, AFile) => Task[RDD[String]] =
          (sc: SparkContext, file: AFile) => Task.delay {
            sc.parallelize(List("""{"name" : "tom", "age" : 28}"""))
          }
        val compile: AlgebraM[SparkState, Const[ShiftedRead, ?], RDD[Data]] = sr.plan(fromFile)
        val afile: AFile = rootDir </> dir("Users") </> dir("rabbit") </> file("test.json")

        val program: SparkState[RDD[Data]] = compile(Const(ShiftedRead(afile, IncludeId)))
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            val results = rdd.collect
            results.size must_= 1
            results(0) must_= Data.Obj(ListMap(
              "name" -> Data.Str("tom"),
              "age" -> Data.Int(28)
            ))
        })
      }.unsafePerformSync
    }

    "core.map" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US")))
        ))

        def func: FreeMap = ProjectFieldR(HoleF, StrLit("country"))
        val map = quasar.qscript.Map(src, func)

        val program: SparkState[RDD[Data]] = compileCore(map)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            val results = rdd.collect
            results.size must_= 3
            results(0) must_= Data.Str("Poland")
            results(1) must_= Data.Str("Poland")
            results(2) must_= Data.Str("US")
        })
      }.unsafePerformSync
    }

    "core.reduce" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US")))
        ))

        def bucket: FreeMap = ProjectFieldR(HoleF, StrLit("country"))
        def reducers: List[ReduceFunc[FreeMap]] = List(Max(ProjectFieldR(HoleF, StrLit("age"))))
        def repair: Free[MapFunc, ReduceIndex] = Free.point(ReduceIndex(0))
        val reduce = Reduce(src, bucket, reducers, repair)

        val program: SparkState[RDD[Data]] = compileCore(reduce)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            val results = rdd.collect
            results.size must_= 2
            results(1) must_= Data.Int(32)
            results(0) must_= Data.Int(23)
        })
      }.unsafePerformSync
    }

    "core.filter" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US")))
        ))

        def func: FreeMap = Free.roll(Lt(ProjectFieldR(HoleF, StrLit("age")), IntLit(24)))
        val filter = quasar.qscript.Filter(src, func)

        val program: SparkState[RDD[Data]] = compileCore(filter)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            val results = rdd.collect
            results.size must_= 1
            results(0) must_= Data.Obj(ListMap(
              "age" -> Data.Int(23),
              "country" -> Data.Str("US")
            ))
        })
      }.unsafePerformSync
    }

    "core.take" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("US")))
        ))

        def from: FreeQS = Free.point(SrcHole)
        def count: FreeQS = constFreeQS(1)

        val take = quasar.qscript.Subset(src, from, Take, count)

        val program: SparkState[RDD[Data]] = compileCore(take)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            val results = rdd.collect
            results.size must_= 1
            results(0) must_= Data.Obj(ListMap(
              "age" -> Data.Int(24),
              "country" -> Data.Str("Poland")
            ))
        })
      }.unsafePerformSync
    }

    "core.drop" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("US")))
        ))

        def from: FreeQS = Free.point(SrcHole)
        def count: FreeQS = constFreeQS(3)

        val drop = quasar.qscript.Subset(src, from, Drop, count)

        val program: SparkState[RDD[Data]] = compileCore(drop)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            val results = rdd.collect
            results.size must_= 1
            results(0) must_= Data.Obj(ListMap(
              "age" -> Data.Int(32),
              "country" -> Data.Str("US")
            ))
        })
      }.unsafePerformSync
    }

    "core.union" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US"))),
          Data.Obj(ListMap() + ("age" -> Data.Int(14)) + ("country" -> Data.Str("UK")))
        ))

        def func(country: String): FreeMap =
          Free.roll(Eq(ProjectFieldR(HoleF, StrLit("country")), StrLit(country)))

        def left: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("Poland"))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("US"))))

        val union = quasar.qscript.Union(src, left, right)

        val program: SparkState[RDD[Data]] = compileCore(union)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            rdd.collect.toList must_= List(
              Data.Obj(ListMap() + ("age" -> Data.Int(24)) + ("country" -> Data.Str("Poland"))),
              Data.Obj(ListMap() + ("age" -> Data.Int(32)) + ("country" -> Data.Str("Poland"))),
              Data.Obj(ListMap() + ("age" -> Data.Int(23)) + ("country" -> Data.Str("US")))
            )
        })
      }.unsafePerformSync
    }

    "core.leftshift" in {
      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap(("age" -> Data.Int(24)),("countries" -> Data.Arr(List(Data.Str("Poland"), Data.Str("US")))))),
          Data.Obj(ListMap(("age" -> Data.Int(24)),("countries" -> Data.Arr(List(Data.Str("UK"))))))
        ))

        def struct: FreeMap = ProjectFieldR(HoleF, StrLit("countries"))
        def repair: JoinFunc = Free.point(RightSide)

        val leftShift = quasar.qscript.LeftShift(src, struct, repair)

        val program: SparkState[RDD[Data]] = compileCore(leftShift)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            rdd.collect.toList must_= List(
              Data.Str("Poland"),
              Data.Str("US"),
              Data.Str("UK")
            )
        })

      }.unsafePerformSync
    }

    "equiJoin.inner" in {

      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US")))),
          Data.Obj(ListMap(("age" -> Data.Int(14)), ("country" -> Data.Str("UK"))))
        ))

        def func(country: String): FreeMap =
          Free.roll(Eq(ProjectFieldR(HoleF, StrLit("country")), StrLit(country)))

        def left: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("Poland"))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("US"))))
        def key: FreeMap = ProjectFieldR(HoleF, StrLit("age"))
        def combine: JoinFunc = Free.roll(ConcatMaps(
          Free.roll(MakeMap(StrLit("left"), LeftSideF)),
          Free.roll(MakeMap(StrLit("right"), RightSideF))
        ))

        val equiJoin = quasar.qscript.EquiJoin(src, left, right, key, key, Inner, combine)

        val program: SparkState[RDD[Data]] = compileJoin(equiJoin)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            rdd.collect.toList must_= List(
              Data.Obj(ListMap(
                "left" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
                "right" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US"))))
              )
            ))
        })
      }.unsafePerformSync
    }

    "equiJoin.leftOuter" in {

      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US")))),
          Data.Obj(ListMap(("age" -> Data.Int(14)), ("country" -> Data.Str("UK"))))
        ))

        def func(country: String): FreeMap =
          Free.roll(Eq(ProjectFieldR(HoleF, StrLit("country")), StrLit(country)))

        def left: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("Poland"))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("US"))))
        def key: FreeMap = ProjectFieldR(HoleF, StrLit("age"))
        def combine: JoinFunc = Free.roll(ConcatMaps(
          Free.roll(MakeMap(StrLit("left"), LeftSideF)),
          Free.roll(MakeMap(StrLit("right"), RightSideF))
        ))

        val equiJoin = quasar.qscript.EquiJoin(src, left, right, key, key, LeftOuter, combine)

        val program: SparkState[RDD[Data]] = compileJoin(equiJoin)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            rdd.collect.toList must_= List(
              Data.Obj(ListMap(
                "left" -> Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("Poland")))),
                "right" -> Data.Null
              )),
              Data.Obj(ListMap(
                "left" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
                "right" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US"))))
              ))
              )
        })
      }.unsafePerformSync
    }

    "equiJoin.rightOuter" in {

      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("US")))),
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US")))),
          Data.Obj(ListMap(("age" -> Data.Int(14)), ("country" -> Data.Str("UK"))))
        ))

        def func(country: String): FreeMap =
          Free.roll(Eq(ProjectFieldR(HoleF, StrLit("country")), StrLit(country)))

        def left: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("Poland"))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("US"))))
        def key: FreeMap = ProjectFieldR(HoleF, StrLit("age"))
        def combine: JoinFunc = Free.roll(ConcatMaps(
          Free.roll(MakeMap(StrLit("left"), LeftSideF)),
          Free.roll(MakeMap(StrLit("right"), RightSideF))
        ))

        val equiJoin = quasar.qscript.EquiJoin(src, left, right, key, key, RightOuter, combine)

        val program: SparkState[RDD[Data]] = compileJoin(equiJoin)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            rdd.collect.toList must_= List(
              Data.Obj(ListMap(
                "left" ->  Data.Null,
                "right" -> Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("US"))))
              )),
              Data.Obj(ListMap(
                "left" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
                "right" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US"))))
              ))
              )
        })
      }.unsafePerformSync
    }

    "equiJoin.fullOuter" in {

      withSparkContext { sc =>

        val src: RDD[Data] = sc.parallelize(List(
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(27)), ("country" -> Data.Str("Poland")))),
          Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("US")))),
          Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US")))),
          Data.Obj(ListMap(("age" -> Data.Int(14)), ("country" -> Data.Str("UK"))))
        ))

        def func(country: String): FreeMap =
          Free.roll(Eq(ProjectFieldR(HoleF, StrLit("country")), StrLit(country)))

        def left: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("Poland"))))
        def right: FreeQS = Free.roll(QCT.inj(Filter(HoleQS, func("US"))))
        def key: FreeMap = ProjectFieldR(HoleF, StrLit("age"))
        def combine: JoinFunc = Free.roll(ConcatMaps(
          Free.roll(MakeMap(StrLit("left"), LeftSideF)),
          Free.roll(MakeMap(StrLit("right"), RightSideF))
        ))

        val equiJoin = quasar.qscript.EquiJoin(src, left, right, key, key, FullOuter, combine)

        val program: SparkState[RDD[Data]] = compileJoin(equiJoin)
        program.eval(sc).run.map(_ must beRightDisjunction.like {
          case rdd =>
            rdd.collect.toList must_= List(
              Data.Obj(ListMap(
                "left" ->  Data.Obj(ListMap(("age" -> Data.Int(27)), ("country" -> Data.Str("Poland")))),
                "right" -> Data.Null
              )),
              Data.Obj(ListMap(
                "left" ->  Data.Null,
                "right" -> Data.Obj(ListMap(("age" -> Data.Int(32)), ("country" -> Data.Str("US"))))
              )),
              Data.Obj(ListMap(
                "left" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("Poland")))),
                "right" -> Data.Obj(ListMap(("age" -> Data.Int(24)), ("country" -> Data.Str("US"))))
              ))
              )
        })
      }.unsafePerformSync
    }

  }

  private def constFreeQS(v: Int): FreeQS =
    Free.roll(QCT.inj(quasar.qscript.Map(Free.roll(QCT.inj(Unreferenced())), IntLit(v))))


  private val emptyFF: (SparkContext, AFile) => Task[RDD[String]] =
    (sc: SparkContext, file: AFile) => Task.delay {
      sc.parallelize(List())
    }

  private def withSparkContext[A](f: SparkContext => Task[A]): Task[A] = {
    val config = new SparkConf().setMaster("local").setAppName("PlannerSpec")
    for {
      sc     <- Task.delay(new SparkContext(config))
      result <- f(sc).onFinish(_ => Task.delay(sc.stop))
    } yield result
  }
}
