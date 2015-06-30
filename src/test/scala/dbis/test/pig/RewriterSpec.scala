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
package dbis.test.pig

import dbis.pig.op._
import dbis.pig.plan.DataflowPlan
import dbis.pig.plan.rewriting.Rewriter._
import org.scalatest.{FlatSpec, Matchers}

class RewriterSpec extends FlatSpec with Matchers{
  "The rewriter" should "merge two Filter operations" in {
    val op1 = Load("a", "file.csv")
    val predicate1 = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))
    val predicate2 = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))
    val op2 = Filter("b", "a", predicate1)
    val op3 = Filter("c", "b", predicate2)
    val op4 = Dump("c")
    val op4_2 = op4.copy()
    val opMerged = Filter("c", "a", And(predicate1, predicate2))

    val planUnmerged = new DataflowPlan(List(op1, op2, op3, op4))
    val planMerged = new DataflowPlan(List(op1, opMerged, op4_2))
    val sink = planUnmerged.sinkNodes.head
    val sinkMerged = planMerged.sinkNodes.head

    val rewrittenSink = processSink(sink)
    rewrittenSink.inputs should equal (sinkMerged.inputs)

    val pPlan = processPlan(planUnmerged)
    pPlan.findOperatorForAlias("c").get should be (opMerged)
    pPlan.findOperatorForAlias("a").get.outputs should contain only(opMerged)
  }

  it should "order Filter operations before Order By ones" in {
    val op1 = Load("a", "file.csv")
    val predicate1 = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))

    // ops before reordering
    val op2 = OrderBy("b", "a", List())
    val op3 = Filter("c", "b", predicate1)
    val op4 = Dump("c")

    // ops after reordering
    val op2_2 = Filter("b", "a", predicate1)
    val op3_2 = OrderBy("c", "b", List())
    val op4_2 = op4.copy()

    val plan = new DataflowPlan(List(op1, op2, op3, op4))
    val planReordered = new DataflowPlan(List(op1, op2_2, op3_2, op4_2))
    val sink = plan.sinkNodes.head
    val sinkReordered = planReordered.sinkNodes.head

    val rewrittenSink = processSink(sink)
    rewrittenSink.inputs should equal (sinkReordered.inputs)
  }

  it should "rewrite DataflowPlans without introducing read-before-write conflicts" in {
    val op1 = Load("a", "file.csv")
    val predicate = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))
    val op2 = Filter("b", "a", predicate)
    val op3 = Dump("b")
    val op4 = OrderBy("c", "b", List())
    val op5 = Dump("c")
    val plan = new DataflowPlan(List(op1, op2, op3, op4, op5))

    val newPlan = processPlan(plan)
    // Check that for each operator all operators in its input list are sorted before it in the operator list
    for (op <- newPlan.operators) {
      val currentIndex = newPlan.operators.indexOf(op)
      for (input <- op.inputs.map(_.producer)) {
        val inputIndex = newPlan.operators.indexOf(input)
        assert(currentIndex > inputIndex)
      }
    }
  }

  it should "not reorder operators if the first one has more than one output" in {
    val op1 = Load("a", "file.csv")
    val predicate1 = Lt(RefExpr(PositionalField(1)), RefExpr(Value("42")))

    // ops before reordering
    val op2 = OrderBy("b", "a", List())
    val op3 = Filter("c", "b", predicate1)
    val op4 = Dump("c")
    val op5 = Dump("b")

    val plan = new DataflowPlan(List(op1, op2, op3, op4, op5))
    val sink = plan.sinkNodes.head

    val rewrittenSink = processSink(sink)
    rewrittenSink.inputs.head.name shouldBe "c"
    rewrittenSink.inputs.head.producer should equal (op3)
  }
}
