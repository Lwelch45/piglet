package dbis.piglet.codegen.scala_lang

import dbis.piglet.codegen.{CodeEmitter, CodeGenContext, CodeGenException}
import dbis.piglet.op.Grouping
import dbis.piglet.schema.TupleType

/**
  * Created by kai on 09.12.16.
  */
class GroupingEmitter extends CodeEmitter[Grouping] {
  override def template: String = """<if (expr)>
                                    |        val <out> = <in>.groupBy(t => {<expr>}).map{case (k,v) => <class>(<keyExtr>,v)}
                                    |<else>
                                    |        val <out> = <in>.coalesce(1).glom.map(t => <class>("all", t))
                                    |<endif>""".stripMargin


  override def code(ctx: CodeGenContext, op: Grouping): String = {
        if (!op.schema.isDefined)
          throw CodeGenException("schema required in GROUPING")
        val className = ScalaEmitter.schemaClassName(op.schema.get.className)

        // GROUP ALL: no need to generate a key
        if (op.groupExpr.keyList.isEmpty)
          render(Map("out" -> op.outPipeName, "in" -> op.inPipeName, "class" -> className))
        else {
          val keyExtr = if (op.groupExpr.keyList.size > 1) {
            // the grouping key consists of multiple fields, i.e. we have
            // to construct a tuple where the type is the TupleType of the group field
            val field = op.schema.get.field("group")
            val className = field.fType match {
              case TupleType(f, c) => ScalaEmitter.schemaClassName(c)
              case _ => throw CodeGenException("unknown type for GROUPING key")
            }

            s"${className}(" + (for (i <- 1 to op.groupExpr.keyList.size) yield s"k._$i").mkString(", ") + ")"
          }
          else "k" // the simple case: the key is a single field

          render(Map("out" -> op.outPipeName, "in" -> op.inPipeName, "class" -> className,
            "expr" -> ScalaEmitter.emitGroupExpr(CodeGenContext(ctx, Map("schema" -> op.inputSchema)), op.groupExpr),
            "keyExtr" -> keyExtr))
        }
  }

}


object GroupingEmitter {
  lazy val instance = new GroupingEmitter
}