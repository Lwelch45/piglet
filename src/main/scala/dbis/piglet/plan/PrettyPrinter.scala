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
package dbis.piglet.plan

import dbis.piglet.op.PigOperator

object PrettyPrinter extends org.kiama.output.PrettyPrinter{
  def pretty(op: PigOperator): String = {
    super.pretty(show(op))
  }

  def show(op: PigOperator): Doc = {
    val prettyInputs = op.inputs.map(p => show(p.producer)).toList
    parens (
      value(op)
      <> nest(
        line
        <> ssep(prettyInputs, line)))
  }

  def show(p: List[PigOperator]): Doc = any(p)
}
