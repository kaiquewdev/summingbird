/*
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.summingbird.scalding.store

import cascading.flow.FlowDef
import com.twitter.bijection.Injection
import com.twitter.scalding.{ TypedPipe, Mode, Dsl, TDsl }
import com.twitter.scalding.commons.source.VersionedKeyValSource
import com.twitter.summingbird.batch.BatchID
import com.twitter.summingbird.scalding.ScaldingEnv

/**
 * Scalding implementation of the batch read and write components
 * of a store that uses the VersionedKeyValSource from scalding-commons.
 *
 * @author Oscar Boykin
 * @author Sam Ritchie
 * @author Ashu Singhal
 */

object VersionedStore {
  def apply[Key, Value](rootPath: String)(implicit injection: Injection[(Key, Value), (Array[Byte], Array[Byte])]) =
    new VersionedStore[Key, Value](VersionedKeyValSource[Key, Value](rootPath))

  def apply[Key, Value](source: => VersionedKeyValSource[Key, Value]) =
    new VersionedStore[Key, Value](source)
}

// TODO: it looks like when we get the mappable/directory this happens
// at a different time (not atomically) with getting the
// meta-data. This seems like something we need to fix: atomically get
// meta-data and open the Mappable.
// The source parameter is pass-by-name to avoid needing the hadoop
// Configuration object when running the storm job.
class VersionedStore[Key, Value](source: => VersionedKeyValSource[Key, Value]) extends BatchStore[Key, Value] {
  import Dsl._
  import TDsl._

  // ## Batch-write Components

  // TODO: the "orElse" logic already exists in the
  // BatchAggregatorJob. Remove it from here.
  def prevUpperBound(env: ScaldingEnv): BatchID =
    HDFSMetadata.get[String](env.config, source.path)
      .map { BatchID(_) }
      .orElse(env.startBatch(env.builder.batcher))
      .get

  // TODO: Note that source is EMPTY if the BatchID doesn't
  // exist. We should really be returning an option of the
  // BatchID, TypedPipe pair.
  def readLatest(env: ScaldingEnv)(implicit fd: FlowDef, mode: Mode): (BatchID, TypedPipe[(Key,Value)]) =
    (prevUpperBound(env), source)

  def write(env: ScaldingEnv, p: TypedPipe[(Key,Value)])
  (implicit fd: FlowDef, mode: Mode) {
    p.toPipe((0,1)).write(source)
  }

  def commit(batchID: BatchID, env: ScaldingEnv) {
    HDFSMetadata.put(env.config, source.path, Some(batchID.toString))
  }
}
