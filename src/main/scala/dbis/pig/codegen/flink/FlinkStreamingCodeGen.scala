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
package dbis.pig.codegen.flink

import dbis.pig.op._
import dbis.pig.expr._
import dbis.pig.udf._
import dbis.pig.schema._
import dbis.pig.plan.DataflowPlan
import dbis.pig.backends.BackendManager

import scala.collection.mutable.ListBuffer

import java.nio.file.Path
import dbis.pig.codegen.ScalaBackendCodeGen
import dbis.pig.codegen.CodeGenerator

class FlinkStreamingCodeGen(template: String) extends ScalaBackendCodeGen(template) {


  /*------------------------------------------------------------------------------------------------- */
  /*                                  Scala-specific code generators                                  */
  /*------------------------------------------------------------------------------------------------- */

  def extractAggregates(node: PigOperator, gen: ForeachGenerator): Tuple3[PigOperator, ForeachGenerator, List[String]]= {
    val udfs = ListBuffer.empty[String]

    var exprs = gen match {
      case GeneratorList(expr) => expr
      case GeneratorPlan(plan) => plan.last.asInstanceOf[Generate].exprs
    }

    var posCounter=0
    exprs = exprs.map( e => e.expr match {
        case Func(f, params) => {
          val pTypes = params.map(p => p.resultType(node.inputSchema))
          UDFTable.findUDF(f, pTypes) match {
            case Some(udf) if (udf.isAggregate) => {
              if(node.inputSchema == None) throw new SchemaException(s"unknown input schema for node $node")
                val newExpr = params.head.asInstanceOf[RefExpr].r match {
                case nf @ NamedField(f, _) => {
                  udfs += s"""("${udf.name}" ,List(${node.inputSchema.get.indexOfField(nf)}))"""
                  PositionalField(node.inputSchema.get.fields.size + posCounter)
                }
                case PositionalField(pos) => {
                  udfs += s"""("${udf.name}", List(${pos}))"""
                  PositionalField(node.inputSchema.get.fields.size + posCounter)
                }
                case DerefTuple(r1, r2) => {
                  udfs += s"""("${udf.name}", List(${emitRef(node.inputSchema, r1, "", false)}, ${emitRef(tupleSchema(node.inputSchema, r1), r2, "")}))"""
                  DerefTuple(r1, PositionalField(tupleSchema(node.inputSchema, r1).get.fields.size + posCounter))
                }
                case _ => ???
              }
              posCounter=posCounter+1
              GeneratorExpr(RefExpr(newExpr), e.alias)
            }
            case _ => GeneratorExpr(e.expr, e.alias)
          }
        }
        case _ => GeneratorExpr(e.expr, e.alias)
      })
    val newGen = gen match {
      case GeneratorList(expr) => GeneratorList(exprs)
      case GeneratorPlan(plan) => {
        var newPlan = plan
        newPlan = newPlan.updated(newPlan.size-1, Generate(exprs))
        node.asInstanceOf[Foreach].subPlan = Option(new DataflowPlan(newPlan))
        GeneratorPlan(newPlan)
      }
    }
    (node, newGen, udfs.toList)
  }

  /**
    *
    * @param schema
    * @param ref
    * @param tuplePrefix
    * @return
    */
  override def emitRef(schema: Option[Schema], ref: Ref, tuplePrefix: String = "t",
                       aggregate: Boolean = false,
                        namedRef: Boolean = false): String = ref match {
      case DerefTuple(r1, r2) => s"${emitRef(schema, r1, "t", false)}.asInstanceOf[Seq[List[Any]]](0)${emitRef(tupleSchema(schema, r1), r2, "")}"
      case _ => super.emitRef(schema, ref, tuplePrefix, aggregate)
  }

  override def emitHelperClass(node: PigOperator): String = { ""
     //require(node.schema.isDefined)
    /*
    val className = schemaClassName(node.schema.get.className)
     node match {
    case Distinct(out, in, windowMode) => {
      if (windowMode) callST("distinctHelper", Map("params"->Map[String,Any]())) else ""
    }
    case Grouping(out, in, groupExpr, windowMode) => {
      if (windowMode) {
        var params = Map[String,Any]()
        params += "out"->node.outPipeName
        params += "expr"->emitGroupExpr(node.inputSchema,groupExpr)
        params += "class" ->className
        callST("groupByHelper", Map("params"->params))
      } else ""
    }
    case Foreach(out, in, gen, windowMode) => {
      if (windowMode) {
        var params = Map[String,Any]()
        params += "out"->node.outPipeName
        params += "expr"->emitForeachExpr(node, gen)
        params += "windowMode"->true
        params += "class" ->className
        callST("foreachHelper", Map("params"->params))
      } else ""
    }
    case Filter(out, in, pred, windowMode) => {
      if(windowMode){
        var params = Map[String,Any]()
        params += "out"->node.outPipeName
        params += "pred"->emitPredicate(node.schema, pred)
        params += "class" ->className
        callST("filterHelper", Map("params"->params))
      } else ""
    }
    case _ => super.emitHelperClass(node)
     }*/
  }

  /**
    * Generates Scala code for a nested plan, i.e. statements within nested FOREACH.
    *
    * @param schema the input schema of the FOREACH statement
    * @param plan the dataflow plan representing the nested statements
    * @return the generated code
    */
  def emitNestedPlan(schema: Option[Schema], plan: DataflowPlan): String = {
    "{\n" + plan.operators.map {
      case Generate(expr) => s"""( ${emitGenerator(schema, expr)} )"""
      case n@ConstructBag(out, ref) => ref match {
        case DerefTuple(r1, r2) => {
          val p1 = findFieldPosition(schema, r1)
          val p2 = findFieldPosition(tupleSchema(schema, r1), r2)
          require(p1 >= 0 && p2 >= 0)
          s"""val ${n.outPipeName} = t($p1).asInstanceOf[Seq[Any]].map(l => l.asInstanceOf[Seq[Any]]($p2))"""
        }
        case _ => "" // should not happen
      }
      case n@Distinct(out, in, windowMode) => s"""val ${n.outPipeName} = ${n.inPipeName}.distinct"""
      case n@Filter(out, in, pred, windowMode) => callST("filter", Map("out" -> n.outPipeName, "in" -> n.inPipeName, "pred" -> emitPredicate(n.schema, pred),"windowMode"->windowMode))
      case OrderBy(out, in, orderSpec, windowMode) => "" // TODO!!!!
      case _ => ""
    }.mkString("\n") + "}"
  }

  def emitForeachExpr(node: PigOperator, gen: ForeachGenerator): String = {
    // we need to know if the generator contains flatten on tuples or on bags (which require flatMap)
    val requiresPlainFlatten =  node.asInstanceOf[Foreach].containsFlatten(onBag = false)
    val requiresFlatMap = node.asInstanceOf[Foreach].containsFlatten(onBag = true)
    gen match {
      case GeneratorList(expr) => {
        if (requiresFlatMap) emitBagFlattenGenerator(node, expr)
          else {
          /*if (requiresPlainFlatten) emitFlattenGenerator(node.inputSchema, expr)
            else*/ emitGenerator(node.inputSchema, expr)
        }
      }
      case GeneratorPlan(plan) => {
        val subPlan = node.asInstanceOf[Foreach].subPlan.get
        emitNestedPlan(node.inputSchema, subPlan)
      }
    }
  }

  def emitStageIdentifier(line: Int, lineage: String): String = ???


  /*------------------------------------------------------------------------------------------------- */
  /*                                   Node code generators                                           */
  /*------------------------------------------------------------------------------------------------- */

  /**
    * Generates code for the CROSS operator.
    *
    * @param node the CROSS operator node
    * @param window window information for Cross' on streams
    * @return the Scala code implementing the CROSS operator
    */
  def emitCross(node: PigOperator, window: Tuple2[Int,String]): String = {
    val rels = node.inputs
    val params =
      if(window != null)
        Map("out" -> node.outPipeName,
          "rel1" -> rels.head.name,
          "rel2" -> rels.tail.map(_.name),
          "window" -> window._1,
          "wUnit" -> window._2)
      else
        Map("out" -> node.outPipeName,
          "rel1" -> rels.head.name,
          "rel2" -> rels.tail.map(_.name))
    callST("cross", params)
  }

  /**
    * Generates code for the DISTINCT Operator
    *
    * @param node the DISTINCT operator node
    * @return the Scala code implementing the DISTINCT operator
    */
  def emitDistinct(node: PigOperator): String = {
    callST("distinct", Map("out" -> node.outPipeName, "in" -> node.inPipeName))
  }

  /**
    * Generates code for the FILTER Operator
    *
    * @param pred the filter predicate
    * @param windowMode true if operator is called within a window environment
    * @return the Scala code implementing the FILTER operator
    */
  def emitFilter(node: PigOperator, pred: Predicate, windowMode: Boolean): String = {
    require(node.schema.isDefined)
    val className = schemaClassName(node.schema.get.className)
    val params =
      if (windowMode)
        Map("out" -> node.outPipeName,
          "in" -> node.inPipeName,
          "pred" -> emitPredicate(node.schema, pred),
          "class" ->className,
          "windowMode" -> windowMode)
      else
        Map("out" -> node.outPipeName,
          "in" -> node.inPipeName,
          "class" ->className,
          "pred" -> emitPredicate(node.schema, pred))
    callST("filter", params)
  }

  /**
    * Generates code for the FOREACH Operator
    *
    * @param node the FOREACH Operator node
    * @param out name of the output bag
    * @param in name of the input bag
    * @param gen the generate expression
    * @param windowMode true if operator is called within a window environment
    * @return the Scala code implementing the FOREACH operator
    */
  def emitForeach(node: PigOperator, out: String, in: String, gen: ForeachGenerator, windowMode: Boolean): String = {
    // we need to know if the generator contains flatten on tuples or on bags (which require flatMap)

    require(node.schema.isDefined)
    val className = schemaClassName(node.schema.get.className)
    
    var generator = gen
    var foreachNode = node
    var aggrs: String = ""

    if (!windowMode){
      val exAggrs: Tuple3[PigOperator,ForeachGenerator, List[String]] = extractAggregates(node,gen)
      generator = exAggrs._2
      foreachNode = exAggrs._1
      if (!exAggrs._3.isEmpty) aggrs = exAggrs._3.toString
    }

    val expr = emitForeachExpr(foreachNode, generator)

    val requiresFlatMap = node.asInstanceOf[Foreach].containsFlatten(onBag = true)
    if (requiresFlatMap)
      if (windowMode)
        callST("foreachFlatMap", Map("out"->out,"in"->in,"expr"->expr,"windowMode"->windowMode, "class" ->className))
      else
        callST("foreachFlatMap", Map("out"->out,"in"->in,"expr"->expr, "class" ->className))
    else
      if (windowMode)
        callST("foreach", Map("out"->out,"in"->in,"expr"->expr,"windowMode"->windowMode, "class" ->className))
      else
        if (aggrs == "")
          callST("foreach", Map("out"->out,"in"->in,"expr"->expr , "class" ->className))
        else
          callST("foreach", Map("out"->out,"in"->in,"expr"->expr,"aggrs"->aggrs, "class" ->className))
  }

  /**
    * Generates code for the GROUPING Operator
    *
    * @param schema the nodes input schema
    * @param out name of the output bag
    * @param in name of the input bag
    * @param groupExpr the grouping expression
    * @param windowMode true if operator is called within a window environment
    * @return the Scala code implementing the GROUPING operator
    */
  def emitGrouping(node: PigOperator, groupExpr: GroupingExpression, windowMode: Boolean): String = {
   val nodeSchema = node.schema
    require(nodeSchema.isDefined)
    val className = schemaClassName(nodeSchema.get.className)
    val out = node.outPipeName
    val in = node.inPipeName
    if (groupExpr.keyList.isEmpty)
      if (windowMode)
        callST("groupBy", Map("out"->out,"in"->in,"windowMode"->windowMode, "class" ->className))
      else
        callST("groupBy", Map("out"->out,"in"->in, "class" ->className))
    else
      if (windowMode)
        callST("groupBy", Map("out"->out,"in"->in,"expr"->emitGroupExpr(nodeSchema, groupExpr),"windowMode"->windowMode, "class" ->className))
      else
        callST("groupBy", Map("out"->out,"in"->in,"expr"->emitGroupExpr(nodeSchema, groupExpr), "class" ->className))
  }

  /**
    * Generates code for the JOIN operator.
    *
    * @param node the Join Operator node
    * @param out name of the output bag
    * @param rels list of Pipes to join
    * @param exprs list of join keys
    * @param window window information for Joins on streams
    * @return the Scala code implementing the JOIN operator
    */
  def emitJoin(node: PigOperator, out: String, rels: List[Pipe], exprs: List[List[Ref]], window: Tuple2[Int,String]): String = {
    val res = node.inputs.zip(exprs)
    val keys = res.map{case (i,k) => emitJoinKey(i.producer.schema, k)}
    if(window!=null)
      callST("join", Map("out"->out,"rel1"->rels.head.name,"key1"->keys.head,"rel2"->rels.tail.map(_.name),"key2"->keys.tail,"window"->window._1,"wUnit"->window._2))
    else
      callST("join", Map("out"->out,"rel1"->rels.head.name,"key1"->keys.head,"rel2"->rels.tail.map(_.name),"key2"->keys.tail))
  }

  /**
    * Generates code for the ORDERBY Operator
    *
    * @param node the OrderBy Operator node
    * @param spec Order specification
    * @param windowMode true if operator is called within a window environment
    * @return the Scala code implementing the ORDERBY operator
    */
  def emitOrderBy(node: PigOperator, spec: List[OrderBySpec], windowMode: Boolean): String = {
    val key = emitSortKey(node.schema, spec, node.outPipeName, node.inPipeName)
    val asc = ascendingSortOrder(spec.head)
    callST("orderBy", Map("out" -> node.outPipeName, "in" -> node.inPipeName, "key" -> key, "asc" -> asc))
  }

  /**
    * Generates code for the SOCKET_READ Operator
    *
    * @param out name of the output bag
    * @param addr the socket address to connect to
    * @param mode the connection mode, e.g. zmq or empty for standard sockets
    * @param streamFunc an optional stream function (we assume a corresponding Scala function is available)
    * @param streamParams an optional list of parameters to a stream function (e.g. separators)
    * @return the Scala code implementing the SOCKET_READ operator
    */
  def emitSocketRead(node: PigOperator, addr: SocketAddress, mode: String, streamFunc: Option[String], streamParams: List[String]): String ={
    var paramMap = super.emitExtractorFunc(node, streamFunc)

    node.schema match {
      case Some(s) => paramMap += ("class" -> schemaClassName(s.className))
      case None => paramMap += ("class" -> "Record")
    }

    val params = if (streamParams != null && streamParams.nonEmpty) ", " + streamParams.mkString(",") else ""
    val func = streamFunc.getOrElse(BackendManager.backend.defaultConnector)
    paramMap ++= Map("out" -> node.outPipeName, "addr_hostname" -> addr.hostname,
      "addr_port" -> addr.port,
      "func" -> func, "params" -> params)
    if (mode != "")
      paramMap += ("mode" -> mode)
    println("paramMap = " + paramMap)
    callST("socketRead", paramMap)

    /*
    val params = if (streamParams != null && streamParams.nonEmpty) ", " + streamParams.mkString(",") else ""
    val func = streamFunc.getOrElse(BackendManager.backend.defaultConnector)
    if(mode!="")
      callST("socketRead", Map("out"->out,"addr"->addr,"mode"->mode,"func"->func,"params"->params))
    else
      callST("socketRead", Map("out"->out,"addr"->addr,"func"->func,"params"->params))
      */
  }

  /**
    * Generates code for the SOCKET_WRITE Operator
    *
    * @param in name of the input bag
    * @param addr the socket address to connect to
    * @param mode the connection mode, e.g. zmq or empty for standard sockets
    * @param streamFunc an optional stream function (we assume a corresponding Scala function is available)
    * @return the Scala code implementing the SOCKET_WRITE operator
    */
  def emitSocketWrite(in: String, addr: SocketAddress, mode: String, streamFunc: Option[String]): String = {
    val func = streamFunc.getOrElse(BackendManager.backend.defaultConnector)
    if(mode!="")
      callST("socketWrite", Map("in"->in,"addr"->addr,"mode"->mode,"func"->func))
    else
      callST("socketWrite", Map("in"->in,"addr"->addr,"func"->func))
  }

  /**
    * Generates code for the WINDOW Operator
    *
    * @param out name of the output bag
    * @param in name of the input bag
    * @param window window size information (Num, Unit)
    * @param slide window slider information (Num, Unit)
    * @return the Scala code implementing the WINDOW operator
    */
  def emitWindow(out: String, in: String, window: Tuple2[Int, String], slide: Tuple2[Int, String]): String = {
    if(window._2==""){
      if(slide._2=="") callST("window", Map("out"-> out,"in"->in, "window"->window._1, "slider"->slide._1))
      else callST("window", Map("out"-> out,"in"->in, "window"->window._1, "slider"->slide._1, "sUnit"->slide._2))
    }
    else {
      if(slide._2=="") callST("window", Map("out"-> out,"in"->in, "window"->window._1, "wUnit"->window._2, "slider"->slide._1))
      else callST("window", Map("out"-> out,"in"->in, "window"->window._1, "wUnit"->window._2, "slider"->slide._1, "sUnit"->slide._2))
    }
  }


  /*------------------------------------------------------------------------------------------------- */
  /*                           implementation of the GenCodeBase interface                            */
  /*------------------------------------------------------------------------------------------------- */

  /**
    * Generate code for the given Pig operator.
    *
    * @param node the operator (an instance of PigOperator)
    * @return a string representing the code
    */
  override def emitNode(node: PigOperator): String = {
    node match {
      /*
       * NOTE: Don't use "out" here -> it refers only to initial constructor argument but isn't consistent
       *       after changing the pipe name. Instead, use node.outPipeName
       */
      case Cross(out, rels, window) => emitCross(node, window)
      case Distinct(out, in, _) => emitDistinct(node)
      case Filter(out, in, pred, windowMode) => emitFilter(node, pred, windowMode)
      case Foreach(out, in, gen, windowMode) => emitForeach(node, node.outPipeName, node.inPipeName, gen, windowMode)
      case Grouping(out, in, groupExpr, windowMode) => emitGrouping(node, groupExpr, windowMode)
      case Join(out, rels, exprs, window) => emitJoin(node, node.outPipeName, node.inputs, exprs, window)
      case OrderBy(out, in, orderSpec, windowMode) => emitOrderBy(node, orderSpec, windowMode)
      case SocketRead(out, address, mode, schema, func, params) => emitSocketRead(node, address, mode, func, params)
      case SocketWrite(in, address, mode, func) => emitSocketWrite(node.inPipeName, address, mode, func)
      case Window(out, in, window, slide) => emitWindow(node.outPipeName,node.inPipeName,window,slide)
      case WindowFlatten(out, in) => callST("windowFlatten", Map("out"->node.outPipeName,"in"->node.inPipeName))
      case _ => super.emitNode(node)
    }
  }

}

class FlinkStreamingGenerator(templateFile: String) extends CodeGenerator {
  override val codeGen = new FlinkStreamingCodeGen(templateFile)
}
