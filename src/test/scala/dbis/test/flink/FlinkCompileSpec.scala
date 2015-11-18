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
package dbis.test.flink

import dbis.test.TestTools._
import dbis.pig.parser.PigParser.parseScript
import dbis.pig.Piglet._
import dbis.pig.codegen.BatchCodeGen
import dbis.pig.op._
import dbis.pig.expr._
import dbis.pig.plan.DataflowPlan
import dbis.pig.schema._
import org.scalatest.FlatSpec
import dbis.pig.backends.BackendManager
import org.scalatest.{Matchers, BeforeAndAfterAll, FlatSpec}
import dbis.pig.plan.rewriting.Rules

class FlinkCompileSpec extends FlatSpec with BeforeAndAfterAll with Matchers {
  
  override def beforeAll()  {
    Rules.registerAllRules()
  }
  
  def cleanString(s: String) : String = s.stripLineEnd.replaceAll("""\s+""", " ").trim
  val backendConf = BackendManager.backend("flink")
  BackendManager.backend = backendConf
  val templateFile = backendConf.templateFile

  "The compiler output" should "contain the Flink header & footer" in {
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitImport 
      + codeGenerator.emitHeader1("test") 
      + codeGenerator.emitHeader2("test") 
      + codeGenerator.emitFooter)
    val expectedCode = cleanString("""
      |import org.apache.flink.api.scala._
      |import dbis.pig.backends.flink._
      |import dbis.pig.backends.{SchemaClass, Record}
      |import org.apache.flink.util.Collector
      |import org.apache.flink.api.common.operators.Order
      |
      |object test {
      |    def main(args: Array[String]) {
      |        val env = ExecutionEnvironment.getExecutionEnvironment
      |    }
      |}
    """.stripMargin)
//         |        env.execute("Starting Query")
  assert(generatedCode == expectedCode)
  }

  it should "contain code for LOAD" in {
    
    val file = new java.io.File(".").getCanonicalPath + "/file.csv"
    
    val op = Load(Pipe("a"), file)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString(s"""val a = PigStorage[Record]().load(env, "$file", (data: Array[String]) => Record(data))""")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for LOAD with PigStorage" in {
    
    val file = new java.io.File(".").getCanonicalPath + "/file.csv"
    
    val op = Load(Pipe("a"), file, None, Some("PigStorage"), List("""','"""))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString(s"""val a = PigStorage[Record]().load(env, "$file", (data: Array[String]) => Record(data), ',')""")
    assert(generatedCode == expectedCode)
  }
/*
  it should "contain code for LOAD with RDFFileStorage" in {
    
    val file = new java.io.File(".").getCanonicalPath + "/file.n3"
    
    val op = Load(Pipe("a"), file, None, Some("RDFFileStorage"))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    
    val expectedCode = cleanString(s"""val a = RDFFileStorage().load(env, "${file}")""")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for FILTER" in { 
    val op = Filter(Pipe("a"), Pipe("b"), Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))) 
    val codeGenerator = new BatchCodeGen(templateFile) 
    val generatedCode = cleanString(codeGenerator.emitNode(op)) 
    val expectedCode = cleanString("val a = b.filter(t => {t(1) < 42})") 
    assert(generatedCode == expectedCode) 
  }
  */
  it should "contain code for DUMP" in {
    val op = Dump(Pipe("a"))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""a.map(_.mkString()).print""")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for STORE" in {
    val file = new java.io.File(".").getCanonicalPath + "/file.csv"
    val op = Store(Pipe("A"), file)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString(s"""PigStorage[Record]().write("$file", A)""".stripMargin)
    assert(generatedCode == expectedCode)
  }
  
  
  it should "contain code for FILTER" in {
    val op = Filter(Pipe("aa"), Pipe("bb"), Lt(RefExpr(PositionalField(1)), RefExpr(Value(42))))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val aa = bb.filter(t => {t.get(1) < 42})")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a complex FILTER" in {
    val ops = parseScript(
      """b = LOAD 'file' AS (x: double, y:double, z1:int, z2: int);
        |c = FILTER b BY x > 0 AND (y < 0 OR (NOT z1 == z2));""".stripMargin)
    val plan = new DataflowPlan(ops)
    val op = ops(1)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val c = b.filter(t => {t._0 > 0 && (t._1 < 0 || (!(t._2 == t._3)))})")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a filter with a function expression" in {
    Schema.init()
    val op = Filter(Pipe("a"), Pipe("b"), Gt(
        Func("aFunc", List(RefExpr(PositionalField(0)), RefExpr(PositionalField(1)))),
        RefExpr(Value("0"))))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val a = b.filter(t => {aFunc(t.get(0),t.get(1)) > 0})")
    assert(generatedCode == expectedCode)
  }
  
  it should "contain code for a filter with a function expression and boolean" in {
    Schema.init()
    val op =  Filter(Pipe("a"),Pipe("b"),And(
            Eq(Func("aFunc",List(RefExpr(NamedField("x")), RefExpr(NamedField("y")))),RefExpr(Value(true))),
            Geq(Func("cFunc",List(RefExpr(NamedField("x")), RefExpr(NamedField("y")))),RefExpr(NamedField("x")))),false)
    op.schema = Some(Schema(Array(Field("x", Types.IntType),
                                                        Field("y", Types.DoubleType))))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
      |val a = b.filter(t => {aFunc(t._0,t._1) == true && cFunc(t._0,t._1) >= t._0})
      |""".stripMargin)
    assert(generatedCode == expectedCode)
  }
  
  it should "contain code for DISTINCT" in {
    val op = Distinct(Pipe("a"), Pipe("b"))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val a = b.distinct")
    assert(generatedCode == expectedCode)
  }
  it should "contain code for LIMIT" in {
    val op = Limit(Pipe("a"), Pipe("b"), 10)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val a = b.first(10)")
    assert(generatedCode == expectedCode)
  }

 it should "contain code a foreach statement with function expressions" in {
    Schema.init()
    // a = FOREACH b GENERATE TOMAP("field1", $0, "field2", $1);
    val op = Foreach(Pipe("aa"), Pipe("bb"), GeneratorList(List(
      GeneratorExpr(Func("TOMAP", List(
        RefExpr(Value("\"field1\"")),
        RefExpr(PositionalField(0)),
        RefExpr(Value("\"field2\"")),
        RefExpr(PositionalField(1)))))
    )))
    op.constructSchema
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val aa = bb.map(t => _t1_Tuple(PigFuncs.toMap(\"field1\",t.get(0),\"field2\",t.get(1))))")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a foreach statement with another function expression" in {
    Schema.init()
    // a = FOREACH b GENERATE $0, COUNT($1) AS CNT;
    val op = Foreach(Pipe("aa"), Pipe("bb"), GeneratorList(List(
        GeneratorExpr(RefExpr(PositionalField(0))),
        GeneratorExpr(Func("COUNT", List(RefExpr(PositionalField(1)))), Some(Field("CNT", Types.LongType)))
      )))
    op.constructSchema
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val aa = bb.map(t => _t1_Tuple(t.get(0), PigFuncs.count(t.get(1))))")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a foreach statement with a UDF expression" in {
    // aa = FOREACH bb GENERATE $0, distance($1, $2, 1.0, 2.0) AS dist;
    val ops = parseScript(
      """
      |bb = LOAD 'file' AS (f1: int, f2: int, f3: int);
      |aa = FOREACH bb GENERATE $0, Distances.spatialDistance($1, $2, 1.0, 2.0) AS dist;
    """.stripMargin)
    val plan = new DataflowPlan(ops)
    val op = plan.findOperatorForAlias("aa").get
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val aa = bb.map(t => _t2_Tuple(t._0, Distances.spatialDistance(t._1,t._2,1.0,2.0)))")
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a foreach statement with a UDF alias expression" in {
    // aa = FOREACH bb GENERATE $0, distance($1, $2, 1.0, 2.0) AS dist;
    val ops = parseScript(
      """bb = LOAD 'data.csv' AS (t1: int, t2: int, t3: int, t4: int);
        |DEFINE distance Distances.spatialDistance();
        |aa = FOREACH bb GENERATE $0, distance($1, $2, 1.0, 2.0) AS dist;
        |""".stripMargin)
    val plan = new DataflowPlan(ops)
    val op = plan.findOperatorForAlias("aa").get
    // op.constructSchema
    val codeGenerator = new BatchCodeGen(templateFile)
    // this is just a hack for this test: normally, the udfAliases map is set in compile
    codeGenerator.udfAliases = Some(plan.udfAliases.toMap)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val aa = bb.map(t => _t2_Tuple(t._0, Distances.spatialDistance(t._1,t._2,1.0,2.0)))")
    assert(generatedCode == expectedCode)
  }
/*
  it should "contain code for deref operator on maps in FOREACH statement" in {
    // a = FOREACH b GENERATE $0#"k1", $1#"k2";
    val op = Foreach(Pipe("a"), Pipe("b"), GeneratorList(List(GeneratorExpr(RefExpr(DerefMap(PositionalField(0), "\"k1\""))),
      GeneratorExpr(RefExpr(DerefMap(PositionalField(1), "\"k2\""))))))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
        |val a = b.map(t => List(t(0).asInstanceOf[Map[String,Any]]("k1"),t(1).asInstanceOf[Map[String,Any]]("k2")))""".stripMargin)
    assert(generatedCode == expectedCode)
  }

  it should "contain code for deref operator on tuple in FOREACH statement" in {
    // a = FOREACH b GENERATE $0.$1, $2.$0;
    val op = Foreach(Pipe("a"), Pipe("b"), GeneratorList(List(GeneratorExpr(RefExpr(DerefTuple(PositionalField(0), PositionalField(1)))),
      GeneratorExpr(RefExpr(DerefTuple(PositionalField(2), PositionalField(0)))))))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
      |val a = b.map(t => List(t(0).asInstanceOf[List[Any]](1),t(2).asInstanceOf[List[Any]](0)))""".stripMargin)
    assert(generatedCode == expectedCode)
  }
*/
  it should "contain code for a UNION operator on two relations" in {
    // a = UNION b, c;
    val op = Union(Pipe("a"), List(Pipe("b"), Pipe("c")))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
        |val a = b.union(c)""".stripMargin)
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a UNION operator on more than two relations" in {
    // a = UNION b, c, d;
    val op = Union(Pipe("a"), List(Pipe("b"), Pipe("c"), Pipe("d")))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
        |val a = b.union(c).union(d)""".stripMargin)
    assert(generatedCode == expectedCode)
  }
/*
  it should "contain code for a binary JOIN statement with simple expression" in {
    val op = Join(Pipe("a"), List(Pipe("b"), Pipe("c")), List(List(PositionalField(0)), List(PositionalField(0))))
    val schema = new Schema(BagType(TupleType(Array(Field("f1", Types.CharArrayType),
                                                              Field("f2", Types.DoubleType),
                                                              Field("f3", Types.IntType)))))
    val input1 = Pipe("b",Load(Pipe("b"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    val input2 = Pipe("c",Load(Pipe("c"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    op.inputs=List(input1,input2)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
        |val a = b.join(c).where(t => t(0).asInstanceOf[String]).equalTo(t => t(0).asInstanceOf[String]).map{
        |t => t._1 ++ t._2
        |}""".stripMargin)
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a binary JOIN statement with expression lists" in {
    val op = Join(Pipe("a"), List(Pipe("b"), Pipe("c")), List(List(PositionalField(0), PositionalField(1)),
      List(PositionalField(1), PositionalField(2))))
    val schema = new Schema(BagType(TupleType(Array(Field("f1", Types.CharArrayType),
                                                              Field("f2", Types.DoubleType),
                                                              Field("f3", Types.IntType)))))
    val input1 = Pipe("b",Load(Pipe("b"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    val input2 = Pipe("c",Load(Pipe("c"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    op.inputs=List(input1,input2)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
        |val a = b.join(c).where(t => Array(t(0).asInstanceOf[String],t(1).asInstanceOf[Double]).mkString).equalTo(t => Array(t(1).asInstanceOf[Double],t(2).asInstanceOf[Int]).mkString).map{
        |t => t._1 ++ t._2
        |}""".stripMargin)
    assert(generatedCode == expectedCode)
  }

  it should "contain code for a multiway JOIN statement" in {
    val op = Join(Pipe("a"), List(Pipe("b"), Pipe("c"), Pipe("d")), List(List(PositionalField(0)),
      List(PositionalField(0)), List(PositionalField(0))))
    val schema = new Schema(BagType(TupleType(Array(Field("f1", Types.CharArrayType),
                                                              Field("f2", Types.DoubleType),
                                                              Field("f3", Types.IntType)))))
    val input1 = Pipe("b",Load(Pipe("b"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    val input2 = Pipe("c",Load(Pipe("c"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    val input3 = Pipe("d",Load(Pipe("d"), "file.csv", Some(schema), Some("PigStorage"), List("\",\"")))
    op.inputs=List(input1,input2,input3)
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
      |val a = b.join(c).where(t => t(0).asInstanceOf[String]).equalTo(t => t(0).asInstanceOf[String]).map{ 
        |t => t._1 ++ t._2
        |}.join(d).where(t => t(0).asInstanceOf[String]).equalTo(t => t(0).asInstanceOf[String]).map{
        |t => t._1 ++ t._2
        |}""".stripMargin)
    assert(generatedCode == expectedCode)
  }

  it should "contain code for GROUP BY ALL" in {
    val op = Grouping(Pipe("a"), Pipe("b"), GroupingExpression(List()))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("val a = b"
      /*"""
        |val fields = new ListBuffer[Int]
        |for(i <- 0 to b.getType.getTotalFields()-1)(fields+=i)
        |val a = b.groupBy(fields.toList:_*)
        |""".stripMargin*/
    )
    assert(generatedCode == expectedCode)
  }
*/
   it should "contain code for GROUP BY $0" in {
    val ops = parseScript(
      """
        |bb = LOAD 'file.csv' USING PigStorage(',') AS (f1: int, f2: chararray, f3: double);
        |aa = GROUP bb BY f1;
      """.stripMargin
    )
    val plan = new DataflowPlan(ops)
    val op = plan.findOperatorForAlias("aa").get
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
      |val aa = A.groupBy(t => t._0).reduceGroup{ (in, out: Collector[_t2_Tuple]) => val itr = in.toIterable; out.collect(_t2_Tuple(itr.head._0, itr)) }""".stripMargin)
    assert(generatedCode == expectedCode)
  }
   
   it should "contain code for simple ORDER BY" in {
    // aa = ORDER bb BY $0
    val op = OrderBy(Pipe("aa"), Pipe("bb"), List(OrderBySpec(PositionalField(0), OrderByDirection.AscendingOrder)))
    val codeGenerator = new BatchCodeGen(templateFile)
    val generatedCode = cleanString(codeGenerator.emitNode(op))
    val expectedCode = cleanString("""
        |val aa = bb.setParallelism(1).sortPartition(t.get(0), Order.true)""".stripMargin)
   println(generatedCode) 
   assert(generatedCode == expectedCode)
  }

}
