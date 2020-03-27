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
package io.smartdatalake.workflow.connection

import java.sql.{DriverManager, ResultSet, Statement, Connection => SqlConnection}

import com.typesafe.config.Config
import io.smartdatalake.config.SdlConfigObject.ConnectionId
import io.smartdatalake.config.{FromConfigFactory, InstanceRegistry}
import io.smartdatalake.util.misc.{CredentialsUtil, SmartDataLakeLogger}

/**
 * Connection information for jdbc tables.
 * If authentication is needed, user and password must be provided.
 *
 * @param id unique id of this connection
 * @param url jdbc connection url
 * @param driver class name of jdbc driver
 * @param userVariable variable used to get the user
 * @param passwordVariable variable used to get the password
 * @param db jdbc database
 * @param metadata
 */
case class JdbcTableConnection( override val id: ConnectionId,
                                url: String,
                                driver: String,
                                userVariable: Option[String] = None,
                                passwordVariable: Option[String] = None,
                                db: Option[String] = None,
                                override val metadata: Option[ConnectionMetadata] = None
                               ) extends Connection with SmartDataLakeLogger {

  require((userVariable.isEmpty && passwordVariable.isEmpty) || (userVariable.isDefined && passwordVariable.isDefined), s"userVariable and passwordVariable must both be empty or both be defined. Please fix for $toStringShort")

  val user: Option[String] = userVariable.map(CredentialsUtil.getCredentials)
  val password: Option[String] = passwordVariable.map(CredentialsUtil.getCredentials)

  def execJdbcStatement(sql:String ) : Boolean = {
    var conn: SqlConnection = null
    var stmt: Statement = null
    try {
      Class.forName(driver)
      conn = getConnection
      stmt = conn.createStatement
      logger.info(s"execJdbcStatement: $sql")
      stmt.execute(sql)
    }
    finally {
      if (stmt!=null) stmt.close()
      if (conn!=null) conn.close()
    }
  }

  def execJdbcQuery[A](sql:String, evalResultSet: ResultSet => A ) : A = {
    var conn: SqlConnection = null
    var stmt: Statement = null
    var rs: ResultSet = null
    try {
      Class.forName(driver)
      conn = getConnection
      stmt = conn.createStatement
      logger.info(s"execJdbcQuery: $sql")
      rs = stmt.executeQuery(sql)
      evalResultSet( rs )
    }
    finally {
      if (rs!=null) rs.close()
      if (stmt!=null) stmt.close()
      if (conn!=null) conn.close()
    }
  }

  private def getConnection: SqlConnection = {
    if (user.isDefined) DriverManager.getConnection(url, user.get, password.get)
    else DriverManager.getConnection(url)
  }

  /**
   * @inheritdoc
   */
  override def factory: FromConfigFactory[Connection] = JdbcTableConnection
}

object JdbcTableConnection extends FromConfigFactory[Connection] {

  /**
   * @inheritdoc
   */
  override def fromConfig(config: Config, instanceRegistry: InstanceRegistry): JdbcTableConnection = {
    import configs.syntax.ConfigOps
    import io.smartdatalake.config._

    implicit val instanceRegistryImpl: InstanceRegistry = instanceRegistry
    config.extract[JdbcTableConnection].value
  }
}


