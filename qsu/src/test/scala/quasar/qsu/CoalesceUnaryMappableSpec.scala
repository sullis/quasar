/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.qsu

import quasar.{Qspec, TreeMatchers}
import quasar.contrib.iota._
import quasar.contrib.matryoshka._
import quasar.contrib.pathy.AFile
import quasar.ejson.{EJson, Fixed}
import quasar.ejson.implicits._
import quasar.fp._
import quasar.qscript.construction

import matryoshka._
import matryoshka.data._
import pathy.Path._

object CoalesceUnaryMappableSpec extends Qspec with QSUTTypes[Fix] with TreeMatchers {
  import QSUGraph.Extractors._

  val qsu = QScriptUniform.DslT[Fix]
  val rec = construction.RecFunc[Fix]
  val mf = construction.Func[Fix]
  val J = Fixed[Fix[EJson]]

  val dataA: AFile = rootDir </> file("dataA")

  val coalesce = CoalesceUnaryMappable[Fix] _

  "coalescing mappable regions having a single root" should {
    "be the identity for a Map applied to a non-mappable root" >> {
      val g = QSUGraph.fromTree[Fix](
        qsu.map(
          qsu.read(dataA),
          rec.ProjectKeyS(rec.Hole, "A")))

      coalesce(g) must beLike {
        case Map(Read(p), f) =>
          val exp = mf.ProjectKeyS(mf.Hole, "A")
          p must_= dataA
          f.linearize must beTreeEqual(exp)
      }
    }

    "compose the functions of adjacent Map nodes" >> {
      val g = QSUGraph.fromTree[Fix](
        qsu.map(
          qsu.map(
            qsu.map(
              qsu.read(dataA),
              rec.ProjectKeyS(rec.Hole, "X")),
            rec.ProjectKeyS(rec.Hole, "Y")),
          rec.ProjectKeyS(rec.Hole, "Z")))

      coalesce(g) must beLike {
        case Map(Read(p), f) =>
          val exp =
            mf.ProjectKeyS(mf.ProjectKeyS(mf.ProjectKeyS(mf.Hole, "X"), "Y"), "Z")

          p must_= dataA
          f.linearize must beTreeEqual(exp)
      }
    }
  }
}