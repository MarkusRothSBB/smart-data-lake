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
package io.smartdatalake.definitions

import io.smartdatalake.util.misc.CredentialsUtil

/**
 * Authentication modes define how an application authenticates itself
 * to a given data object/connection
 *
 * You need to define one of the AuthModes (subclasses) as type, i.e.
 * {{{
 * authMode {
 *   type = BasicAuthMode
 *   user = myUser
 *   password = myPassword
 * }
 * }}}
 *
 */
sealed trait AuthMode

/**
 * Derive options for various connection types to connect by basic authentication
 */
case class BasicAuthMode(userVariable: String, passwordVariable: String) extends AuthMode {
  private[smartdatalake] val user: String = CredentialsUtil.getCredentials(userVariable)
  private[smartdatalake] val password: String = CredentialsUtil.getCredentials(passwordVariable)
}

/**
 * Derive options for various connection types to connect by token
  */
case class TokenAuthMode(tokenVariable: String) extends AuthMode {
  private[smartdatalake] val token: String = CredentialsUtil.getCredentials(tokenVariable)
}

/**
 * Validate by user and private/public key
 * Private key is read from .ssh
 */
case class PublicKeyAuthMode(userVariable: String) extends AuthMode {
  private[smartdatalake] val user: String = CredentialsUtil.getCredentials(userVariable)
}

/**
 * Validate by SSL Certificates : Only location an credentials. Additional attributes should be
 * supplied via options map
 */
case class SSLCertsAuthMode (
                            keystorePath: String,
                            keystoreType: Option[String],
                            keystorePassVariable: String,
                            truststorePath: String,
                            truststoreType: Option[String],
                            truststorePassVariable: String
                           ) extends AuthMode {
  private[smartdatalake] val truststorePass: String = CredentialsUtil.getCredentials(truststorePassVariable)
  private[smartdatalake] val keystorePass: String = CredentialsUtil.getCredentials(keystorePassVariable)
}
