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

package quasar.impl

import quasar.api.resource.ResourcePath

import scala.util.Either

import fs2.Stream

package object datasource {
  type AggregateResult[F[_], A] = Stream[F, (ResourcePath, A)]
  type CompositeResult[F[_], A] = Either[A, AggregateResult[F, A]]
}
