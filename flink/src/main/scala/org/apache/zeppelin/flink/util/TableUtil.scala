/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.zeppelin.flink.util


import _root_.java.util.{UUID, ArrayList => JArrayList}

import org.apache.calcite.rel.RelNode
import org.apache.flink.api.common.accumulators.SerializedListAccumulator
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.common.typeutils.TypeSerializer
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment
import org.apache.flink.table.api.internal.{TableEnvironmentImpl, TableImpl}
import org.apache.flink.table.api.{Table, TableEnvironment}
import org.apache.flink.table.planner.calcite.{FlinkRelBuilder, FlinkTypeFactory}
import org.apache.flink.table.planner.delegation.PlannerBase
import org.apache.flink.table.planner.plan.schema.TimeIndicatorRelDataType
import org.apache.flink.table.planner.sinks.{CollectRowTableSink, CollectTableSink}
import org.apache.flink.table.runtime.types.TypeInfoLogicalTypeConverter
import org.apache.flink.table.types.logical.TimestampType
import org.apache.flink.table.types.utils.TypeConversions.fromDataTypeToLegacyInfo
import org.apache.flink.types.Row
import org.apache.flink.util.AbstractID

import _root_.scala.collection.JavaConversions._
import _root_.scala.collection.JavaConverters._

object TableUtil {

  /**
    * Returns an collection that contains all rows in this Table.
    *
    * Note: The difference between print() and collect() is
    * - print() prints data on workers and collect() collects data to the client.
    * - You have to call TableEnvironment.execute() to run the job for print(), while collect()
    * calls execute automatically.
    */
  def collect(table: TableImpl): Seq[Row] = collectSink(table, new CollectRowTableSink, None)

  def collect(table: TableImpl, jobName: String): Seq[Row] =
    collectSink(table, new CollectRowTableSink, Option.apply(jobName))

  def collectAsT[T](table: TableImpl, t: TypeInformation[_], jobName: String = null): Seq[T] =
    collectSink(
      table,
      new CollectTableSink(_ => t.asInstanceOf[TypeInformation[T]]), Option(jobName))

  def collectSink[T](
      table: TableImpl, sink: CollectTableSink[T], jobName: Option[String] = None): Seq[T] = {
    // get schema information of table
    val relNode = toRelNode(table)
    val rowType = relNode.getRowType
    val fieldNames = rowType.getFieldNames.asScala.toArray
    val fieldTypes = rowType.getFieldList.map { field =>
      val `type` = field.getType match {
        // converts `TIME ATTRIBUTE(ROWTIME)`/`TIME ATTRIBUTE(PROCTIME)` to `TIMESTAMP(3)` for sink
        case _: TimeIndicatorRelDataType =>
          relNode.getCluster
            .getTypeFactory.asInstanceOf[FlinkTypeFactory]
            .createFieldTypeFromLogicalType(new TimestampType(false, 3))
        case t => t
      }
      FlinkTypeFactory.toLogicalType(`type`)
    }.toArray
    val configuredSink = sink.configure(
      fieldNames, fieldTypes.map(TypeInfoLogicalTypeConverter.fromLogicalTypeToTypeInfo))
    collect(table.getTableEnvironment,
      table, configuredSink.asInstanceOf[CollectTableSink[T]], jobName)
  }

  /**
    * Converts operation tree in the given table to a RelNode tree.
    */
  def toRelNode(table: Table): RelNode = {
    val plannerBase = table.asInstanceOf[TableImpl]
      .getTableEnvironment.asInstanceOf[TableEnvironmentImpl]
      .getPlanner.asInstanceOf[PlannerBase]

    val method = classOf[PlannerBase].getMethod("getRelBuilder")
    method.setAccessible(true)
    method.invoke(plannerBase).asInstanceOf[FlinkRelBuilder]
      .queryOperation(table.getQueryOperation).build()
  }

  def collect[T](
                  tEnv: TableEnvironment,
                  table: Table,
                  sink: CollectTableSink[T],
                  jobName: Option[String]): Seq[T] = {

    val method = classOf[PlannerBase].getMethod("getExecEnv")
    method.setAccessible(true)
    val execEnv = method.invoke(tEnv.asInstanceOf[TableEnvironmentImpl]
      .getPlanner).asInstanceOf[StreamExecutionEnvironment]

    val typeSerializer = fromDataTypeToLegacyInfo(sink.getConsumedDataType)
      .asInstanceOf[TypeInformation[T]]
      .createSerializer(execEnv.getConfig)
    val id = new AbstractID().toString
    sink.init(typeSerializer.asInstanceOf[TypeSerializer[T]], id)
    val sinkName = UUID.randomUUID().toString

    // workaround, otherwise it won't find the sink properly
    val originalCatalog = tEnv.getCurrentCatalog
    val originalDatabase = tEnv.getCurrentDatabase
    try {
      tEnv.useCatalog("default_catalog")
      tEnv.useDatabase("default_database")
      tEnv.registerTableSink(sinkName, sink)
      tEnv.insertInto(table, sinkName)
      val res = tEnv.execute(jobName.getOrElse("sql collect job"))
      val accResult: JArrayList[Array[Byte]] = res.getAccumulatorResult(id)
      SerializedListAccumulator.deserializeList(accResult, typeSerializer)
    } finally {
      tEnv.useCatalog(originalCatalog)
      tEnv.useDatabase(originalDatabase)
    }
  }
}
