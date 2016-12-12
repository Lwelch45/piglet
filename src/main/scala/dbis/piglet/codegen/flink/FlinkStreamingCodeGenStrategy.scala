package dbis.piglet.codegen.flink

import java.net.URI

import dbis.piglet.codegen.{ CodeEmitter, CodeGenContext }
import dbis.piglet.codegen.scala_lang.ScalaCodeGenStrategy
import dbis.piglet.op.Load
import dbis.piglet.plan.DataflowPlan
import dbis.piglet.tools.Conf
import dbis.piglet.codegen.CodeGenTarget
import dbis.piglet.codegen.scala_lang.DumpEmitter
import dbis.piglet.codegen.scala_lang.LoadEmitter
import dbis.piglet.codegen.scala_lang.StoreEmitter

class FlinkStreamingCodeGenStrategy extends ScalaCodeGenStrategy {
  override val target = CodeGenTarget.FlinkStreaming
  override val emitters = super.emitters + (
    s"$pkg.Load" -> new FlinkStreamingLoadEmitter,
    s"$pkg.Dump" -> new FlinkStreamingDumpEmitter,
    s"$pkg.Store" -> new FlinkStreamingStoreEmitter
  )
  /**
   * Generate code needed for importing required Scala packages.
   *
   * @return a string representing the import code
   */
  override def emitImport(ctx: CodeGenContext, additionalImports: Seq[String] = Seq.empty): String =
    CodeEmitter.render("""import org.apache.flink.streaming.api.scala._
                         |import dbis.piglet.backends.flink._
                         |import dbis.piglet.backends.flink.streaming._
                         |import java.util.concurrent.TimeUnit
                         |import org.apache.flink.util.Collector
                         |import org.apache.flink.streaming.api.windowing.assigners._
                         |import org.apache.flink.streaming.api.windowing.evictors._
                         |import org.apache.flink.streaming.api.windowing.time._
                         |import org.apache.flink.streaming.api.windowing.triggers._
                         |import org.apache.flink.streaming.api.windowing.windows._
                         |import org.apache.flink.streaming.api.TimeCharacteristic
                         |import dbis.piglet.backends.{SchemaClass, Record}
                         |<if (additional_imports)>
                         |<additional_imports>
                         |<endif>
                         |<\n>
                         |""".stripMargin,
      Map("additional_imports" -> additionalImports.mkString("\n")))

  /**
   * Generate code for the header of the script outside the main class/object,
   * i.e. defining the main object.
   *
   * @param scriptName the name of the script (e.g. used for the object)
   * @return a string representing the header code
   */
  override def emitHeader1(ctx: CodeGenContext, scriptName: String): String =
    CodeEmitter.render(
      """object <name> {
        |  val env = StreamExecutionEnvironment.getExecutionEnvironment
        |  env.setStreamTimeCharacteristic(TimeCharacteristic.IngestionTime)
        |""".stripMargin, Map("name" -> scriptName))

  /**
   *
   * Generate code for the header of the script which should be defined inside
   * the main class/object.
   *
   * @param scriptName the name of the script (e.g. used for the object)
   * @param profiling add profiling code to the generated code
   * @return a string representing the header code
   */
  override def emitHeader2(ctx: CodeGenContext, scriptName: String, profiling: Option[URI] = None): String = {
    CodeEmitter.render("""  def main(args: Array[String]) {<\n>""", Map.empty)
  }

  override def emitFooter(ctx: CodeGenContext, plan: DataflowPlan): String = {
    var params = Map("name" -> "Starting Query")
    CodeEmitter.render("""    env.execute("<name>")
                         |<if (hook)>
	                       |    shutdownHook()
                         |<endif>
                         |  }
                         |}""".stripMargin, params)

  }
}

/*------------------------------------------------------------------------------------------------- */
/*                                FlinkStreaming-specific emitters                                  */
/*------------------------------------------------------------------------------------------------- */

class FlinkStreamingLoadEmitter extends LoadEmitter {
  override def template: String = """    val <out> = <func>[<class>]().loadStream(env, "<file>", <extractor><if (params)>, <params><endif>)""".stripMargin
}

class FlinkStreamingStoreEmitter extends StoreEmitter {
  override def template: String = """    <func>[<class>]().writeStream("<file>", <in><if (params)>, <params><endif>)""".stripMargin
}

class FlinkStreamingDumpEmitter extends DumpEmitter {
  override def template: String = """    <in>.map(_.mkString()).print""".stripMargin
}