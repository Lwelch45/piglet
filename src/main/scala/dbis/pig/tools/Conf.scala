package dbis.pig.tools

import com.typesafe.config.ConfigFactory
import java.io.File
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.CopyOption
import com.typesafe.scalalogging.LazyLogging
import com.typesafe.config.Config
import java.nio.file.StandardCopyOption

/**
 * This is the global configuration object that contains all user-defined values
 */
object Conf extends LazyLogging {
  
  /**
   * The path to the config file. It will resolve to $USER_HOME/.piglet/application.conf
   */
  private val configFile = Paths.get(System.getProperty("user.home"), ".piglet", "application.conf")
  
  /**
   * Load the configuration.
   * 
   * This loads the configuration from the user's home directory. If the config file cannot be found
   * (see [[Conf#configFile]]) the default values found in src/main/resources/application.conf are
   * copied to [[Conf#configFile]]
   * 
   * @return Returns the config object
   */
  private def loadConf: Config = {
    
    // 1. check if the config file in the user's home directory exists
    if(!Files.exists(configFile)) {
      
      // 2. if not, create parent directory if necessary
      if(!Files.exists(configFile.getParent)) {
        Files.createDirectories(configFile.getParent)
        logger.info(s"""created program directory at ${configFile.getParent}""")
      }
      
      // 3. copy config file
      copyConfigFile()
    }
  
    // 4. parse the newly created config file
    ConfigFactory.parseFile(configFile.toFile())
  }

  protected[pig] def copyConfigFile() = {
    val source = Conf.getClass.getClassLoader.getResourceAsStream("application.conf")
    Files.copy(source, configFile, StandardCopyOption.REPLACE_EXISTING)
    logger.debug(s"copied config file to $configFile")
  }
  
  // loads the configuration file 
  private val appconf = loadConf
  
  
  def materializationBaseDir: File = new File(appconf.getString("materialization.basedir"))
  def materializationMapFile: File = new File(materializationBaseDir, 
                                                appconf.getString("materialization.mapfile"))
 
  
  def backendJar(backend: String) = appconf.getString(s"backends.$backend.jar")
}