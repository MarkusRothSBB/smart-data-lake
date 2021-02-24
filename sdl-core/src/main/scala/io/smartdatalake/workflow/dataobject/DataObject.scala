/*
 * Smart Data Lake - Build your data lake the smart way.
 *
 * Copyright © 2019-2020 ELCA Informatique SA (<https://www.elca.ch>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package io.smartdatalake.workflow.dataobject

import io.smartdatalake.config.SdlConfigObject.{ConnectionId, DataObjectId}
import io.smartdatalake.config.{ConfigurationException, InstanceRegistry, ParsableFromConfig, SdlConfigObject}
import io.smartdatalake.util.hdfs.PartitionValues
import io.smartdatalake.util.misc.SmartDataLakeLogger
import io.smartdatalake.workflow.{ActionPipelineContext, AtlasExportable}
import io.smartdatalake.workflow.connection.Connection
import org.apache.spark.sql.SparkSession

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/**
 * This is the root trait for every DataObject.
 */
@DeveloperApi
trait DataObject extends SdlConfigObject with ParsableFromConfig[DataObject] with SmartDataLakeLogger with AtlasExportable {

  /**
   * A unique identifier for this instance.
   */
  override val id: DataObjectId

  /**
   * Additional metadata for the DataObject
   */
  def metadata: Option[DataObjectMetadata]

  /**
   * Prepare & test [[DataObject]]'s prerequisits
   *
   * This runs during the "prepare" operation of the DAG.
   */
  def prepare(implicit session: SparkSession): Unit = Unit

  /**
   * Runs operations before reading from [[DataObject]]
   */
  def preRead(partitionValues: Seq[PartitionValues])(implicit session: SparkSession, context: ActionPipelineContext): Unit = Unit

  /**
   * Runs operations after reading from [[DataObject]]
   */
  def postRead(partitionValues: Seq[PartitionValues])(implicit session: SparkSession, context: ActionPipelineContext): Unit = Unit

  /**
   * Runs operations before writing to [[DataObject]]
   * Note: As the transformed SubFeed doesnt yet exist in Action.preWrite, no partition values can be passed as parameters as in preRead
   */
  def preWrite(implicit session: SparkSession, context: ActionPipelineContext): Unit = Unit

  /**
   * Runs operations after writing to [[DataObject]]
   */
  def postWrite(partitionValues: Seq[PartitionValues])(implicit session: SparkSession, context: ActionPipelineContext): Unit = Unit

  /**
   * Handle class cast exception when getting objects from instance registry
   *
   * @param connectionId
   * @param registry
   * @return
   */
  protected def getConnection[T <: Connection](connectionId: ConnectionId)(implicit registry: InstanceRegistry, ct: ClassTag[T], tt: TypeTag[T]): T = {
    val connection: T = registry.get[T](connectionId)
    try {
      // force class cast on generic type (otherwise the ClassCastException is thrown later)
      ct.runtimeClass.cast(connection).asInstanceOf[T]
    } catch {
      case e: ClassCastException =>
        val objClass = connection.getClass.getSimpleName
        val expectedClass = tt.tpe.toString.replaceAll(classOf[DataObject].getPackage.getName+".", "")
        throw ConfigurationException(s"${this.id} needs $expectedClass as connection but $connectionId is of type $objClass")
    }
  }
  protected def getConnectionReg[T <: Connection](connectionId: ConnectionId, registry: InstanceRegistry)(implicit ct: ClassTag[T], tt: TypeTag[T]): T = {
    implicit val registryImpl: InstanceRegistry = registry
    getConnection[T](connectionId)
  }

  def toStringShort: String = {
    s"$id[${this.getClass.getSimpleName}]"
  }

  override def atlasName: String = id.id
}

/**
 * Additional metadata for a DataObject
 * @param name Readable name of the DataObject
 * @param description Description of the content of the DataObject
 * @param layer Name of the layer this DataObject belongs to
 * @param subjectArea Name of the subject area this DataObject belongs to
 * @param tags Optional custom tags for this object
 */
case class DataObjectMetadata(
                               name: Option[String] = None,
                               description: Option[String] = None,
                               layer: Option[String] = None,
                               subjectArea: Option[String] = None,
                               tags: Seq[String] = Seq()
                             )
