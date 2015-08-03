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

package dbis.pig.tools

import java.io.{File, FileOutputStream, FileWriter, InputStream, OutputStream}
import java.util.jar.JarFile
import dbis.pig._
import dbis.pig.codegen.ScalaBackendCompile
import dbis.pig.plan.DataflowPlan
import com.typesafe.scalalogging.LazyLogging

object FileTools extends LazyLogging {
  def copyStream(istream : InputStream, ostream : OutputStream) : Unit = {
    var bytes =  new Array[Byte](1024)
    var len = -1
    while({ len = istream.read(bytes, 0, 1024); len != -1 })
      ostream.write(bytes, 0, len)
  }

  def extractJarToDir(jarName: String, outDir: String): Unit = {
    
    logger.debug(s"extracting jar $jarName to $outDir" )
    
    val jar = new JarFile(jarName)
    val enu = jar.entries
    while (enu.hasMoreElements) {
      val entry = enu.nextElement
      val entryPath = entry.getName

      // we don't need the MANIFEST.MF file
      if (! entryPath.endsWith("MANIFEST.MF")) {

        // println("Extracting to " + outDir + "/" + entryPath)
        if (entry.isDirectory) {
          new File(outDir, entryPath).mkdirs
        }
        else {
          val istream = jar.getInputStream(entry)
          val ostream = new FileOutputStream(new File(outDir, entryPath))
          copyStream(istream, ostream)
          ostream.close
          istream.close
        }
      }
    }
  }
  
  def compileToJar(plan: DataflowPlan, scriptName: String, outDir: String, compileOnly: Boolean = false, backend: String = "spark"): Boolean = {
    // 4. compile it into Scala code for Spark
    val compiler = new ScalaBackendCompile(getTemplateFile(backend)) 

    // 5. generate the Scala code
    val code = compiler.compile(scriptName, plan)

    logger.debug("successfully generated scala program")
    
    // 6. write it to a file

    val outputDir = new File(s"$outDir${File.separator}${scriptName}")
    if(!outputDir.exists()) {
      outputDir.mkdirs()
    }
    

    val outputDirectory = s"${outputDir.getCanonicalPath}${File.separator}out"
    
    // check whether output directory exists
    val dirFile = new File(outputDirectory)
    // if not then create it
    if (!dirFile.exists)
      dirFile.mkdir()

    val outputFile = s"$outputDirectory${File.separator}${scriptName}.scala" //scriptName + ".scala"
    val writer = new FileWriter(outputFile)
    writer.append(code)
    writer.close()

    // 7. extract all additional jar files to output
    plan.additionalJars.foreach(jarFile => FileTools.extractJarToDir(jarFile, outputDirectory))
    
    // 8. copy the sparklib library to output
//    backend match {
//      case "flink" => FileTools.extractJarToDir(Conf.flinkBackendJar, outputDirectory)
//      case "spark" => FileTools.extractJarToDir(Conf.sparkBackendJar, outputDirectory)
//    }
    
    FileTools.extractJarToDir(Conf.backendJar(backend), outputDirectory)
    
//    if (compileOnly) 
//      return false // sys.exit(0)

    // 9. compile the scala code
    if (!ScalaCompiler.compile(outputDirectory, outputFile))
      return false


    // 10. build a jar file
    val jarFile = s"$outDir${File.separator}${scriptName}${File.separator}${scriptName}.jar" //scriptName + ".jar"
    JarBuilder.apply(outputDirectory, jarFile, verbose = false)
    
    logger.info(s"created job's jar file at $jarFile")
    
    true
  }

  private def getTemplateFile(backend: String): String = {
    BuildSettings.backends.get(backend).get("templateFile")
  }

  def getRunner(backend: String): Run = { 
    val className = BuildSettings.backends.get(backend).get("runClass")
    Class.forName(className).newInstance().asInstanceOf[Run]
  }
}