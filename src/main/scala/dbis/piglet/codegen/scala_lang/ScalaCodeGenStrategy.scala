/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dbis.piglet.codegen.scala_lang

import java.net.URI

import dbis.piglet.codegen.{CodeEmitter, CodeGenContext, CodeGenStrategy, CodeGenTarget}
import dbis.piglet.expr.Expr
import dbis.piglet.op._
import dbis.piglet.plan.DataflowPlan
import dbis.piglet.schema._
import dbis.piglet.tools.Conf

import scala.collection.mutable.ListBuffer
import dbis.piglet.tools.logging.PigletLogging
import dbis.piglet.codegen.spark.SpatialFilterEmitter
import dbis.piglet.codegen.spark.SpatialJoinEmitter

abstract class ScalaCodeGenStrategy extends CodeGenStrategy with PigletLogging {
  // initialize target and emitters
  val target = CodeGenTarget.Unknown

  override def collectAdditionalImports(plan: DataflowPlan) = {
    val additionalImports = ListBuffer.empty[String]
    if (plan.checkExpressions(Expr.containsMatrixType)) {
      additionalImports += "import breeze.linalg._"
    }

    additionalImports
  }

  def emitterForNode[O <: PigOperator](op: O): CodeEmitter[O] = {
    val em = op match {
      case _: Load => LoadEmitter.instance
      case _: Filter => FilterEmitter.instance
      case _: Limit => LimitEmitter.instance
      case _: Foreach => ForeachEmitter.instance
      case _: Distinct => DistinctEmitter.instance
      case _: Sample => SampleEmitter.instance
      case _: Union => UnionEmitter.instance
      case _: Grouping => GroupingEmitter.instance
      case _: OrderBy => OrderByEmitter.instance
      case _: Top => TopEmitter.instance
      case _: Accumulate => AccumulateEmitter.instance
      case _: Join => JoinEmitter.instance
      case _: Cross => CrossEmitter.instance
      case _: Dump => DumpEmitter.instance
      case _: Empty => EmptyEmitter.instance
      case _: Store => StoreEmitter.instance
      case _: StreamOp => StreamOpEmitter.instance
      case _: TimingOp => TimingEmitter.instance
      case _: Matcher => MatcherEmitter.instance
      case _ => throw new IllegalArgumentException(s"no emitter for $op")      
    }
  
    em.asInstanceOf[CodeEmitter[O]]
  }

  /**
    * Generate code for embedded code: usually this code is just copied
    * to the generated file.
    *
    * @param additionalCode the code to be embedded
    * @return a string representing the code
    */
  def emitEmbeddedCode(ctx: CodeGenContext, additionalCode: String) = additionalCode

  def emitNode[O <: PigOperator](ctx: CodeGenContext, node: O): String = {
    val className = node.getClass.getName
    
    val emitter = emitterForNode(node)
    
    var code = emitter.beforeCode(ctx, node)
    if (code.length > 0) code += "\n"

    code += emitter.code(ctx, node)

    val afterCode = emitter.afterCode(ctx, node)
    if (afterCode.length > 0)
      code += "\n" + afterCode

    code
  }


  /**
    * Generate code for classes representing schema types.
    *
    * @param schemas the list of schemas for which we generate classes
    * @return a string representing the code
    */
  override def emitSchemaHelpers(ctx: CodeGenContext, schemas: List[Schema]): String = {
    var converterCode = ""

    val classes = ListBuffer.empty[(String, String)]

    for (schema <- schemas) {
      val values = ScalaEmitter.createSchemaInfo(schema)

      classes += ScalaEmitter.emitSchemaClass(values)
      converterCode += ScalaEmitter.emitSchemaConverters(values)
    }

    val p = "_t([0-9]+)_Tuple".r

    val sortedClasses = classes.sortWith { case (left, right) =>
      val leftNum = left._1 match {
        case p(group) => group.toInt
        case _ => throw new IllegalArgumentException(s"unexpected class name: $left")
      }

      val rightNum = right._1 match {
        case p(group) => group.toInt
        case _ => throw new IllegalArgumentException(s"unexpected class name: $left")
      }

      leftNum < rightNum
    }

    val classCode = sortedClasses.map(_._2).mkString("\n")

    classCode + "\n" + converterCode
  }

  /**
    * Generate code for any helper class/function if needed by the given operator.
    *
    * @param node the Pig operator requiring helper code
    * @return a string representing the helper code
    */
  override def emitHelperClass(ctx: CodeGenContext, node: PigOperator): String = {
    val emitter = emitterForNode(node)

    emitter.helper(ctx, node)
  }
}
