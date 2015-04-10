package dbis.pig

/**
 * Created by kai on 08.04.15.
 */
trait GenCodeBase {
  def emitNode(node: PigOperator): String
  def emitHeader(scriptName: String): String
  def emitFooter: String
  def emitPredicate(schema: Option[Schema], predicate: Predicate): String
  def emitRef(schema: Option[Schema], ref: Ref): String
}

trait Compile {
  def codeGen: GenCodeBase

  def compile(scriptName: String, plan: DataflowPlan): String = {
    require(codeGen != null, "code generator undefined")
    var code = codeGen.emitHeader(scriptName)
    for (n <- plan.operators) {
      code = code + codeGen.emitNode(n) + "\n"
    }
    code + codeGen.emitFooter
  }
}

class SparkGenCode extends GenCodeBase {
  def emitRef(schema: Option[Schema], ref: Ref): String = ref match {
    case NamedField(f) => {
      if (schema.isEmpty) throw new SchemaException(s"unknown schema for field $f")
      s"t._${schema.get.indexOfField(f)}"
    } // TODO: should be position of field
    case PositionalField(pos) => { s"t._$pos" }
    case Value(v) => { v.toString } // TODO: could be also a predicate!
    case _ => { "" }
  }

  def emitPredicate(schema: Option[Schema], predicate: Predicate): String = predicate match {
    case Eq(left, right) => { s"${emitRef(schema, left)} == ${emitRef(schema, right)}"}
    case Neq(left, right) => { s"${emitRef(schema, left)} != ${emitRef(schema, right)}"}
    case Leq(left, right) => { s"${emitRef(schema, left)} <= ${emitRef(schema, right)}"}
    case Lt(left, right) => { s"${emitRef(schema, left)} < ${emitRef(schema, right)}"}
    case Geq(left, right) => { s"${emitRef(schema, left)} >= ${emitRef(schema, right)}"}
    case Gt(left, right) => { s"${emitRef(schema, left)} > ${emitRef(schema, right)}"}
    case _ => { "" }
  }

  def emitNode(node: PigOperator): String = node match {
    case Load(out, file) => { s"""val $out = sc.textFile("$file")""" }
    case Dump(in) => { s"${node.inPipeNames(0)}.map(t => println(t))" }
    case Filter(out, in, pred) => { s"val $out = ${node.inPipeNames(0)}.filter(t => {${emitPredicate(node.schema, pred)}})" }
    case _ => { "" }
  }

  def emitHeader(scriptName: String): String = {
    s"""
      |import org.apache.spark.SparkContext
      |import org.apache.spark.SparkContext._
      |import org.apache.spark.SparkConf
      |import org.apache.spark.rdd._
      |
      |object $scriptName {
      |    def main(args: Array[String]) {
      |      val conf = new SparkConf().setAppName("${scriptName}_App")
      |      conf.setMaster("local[4]")
      |      val sc = new SparkContext(conf)
    """.stripMargin
  }

  def emitFooter: String = {
    """
      |      sc.stop()
      }
    }
    """.stripMargin
  }
}

class SparkCompile extends Compile {
  override val codeGen = new SparkGenCode
}