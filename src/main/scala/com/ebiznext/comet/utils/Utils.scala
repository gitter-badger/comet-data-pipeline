/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  * contributor license agreements.  See the NOTICE file distributed with
 *  * this work for additional information regarding copyright ownership.
 *  * The ASF licenses this file to You under the Apache License, Version 2.0
 *  * (the "License"); you may not use this file except in compliance with
 *  * the License.  You may obtain a copy of the License at
 *  *
 *  *    http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 *
 */

package com.ebiznext.comet.utils

import com.ebiznext.comet.schema.model.{Attribute, WriteMode}
import com.typesafe.scalalogging.Logger

import java.io.{PrintWriter, StringWriter}
import scala.reflect.runtime.universe
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

object Utils {

  /** Handle tansparently autocloseable resources and correctly chain exceptions
    *
    * @param r : the resource
    * @param f : the try bloc
    * @tparam T : resource Type
    * @tparam V : Try bloc return type
    * @return : Try bloc return value
    */
  def withResources[T <: AutoCloseable, V](r: => T)(f: T => V): V = {
    val resource: T = r
    require(resource != null, "resource is null")
    var exception: Throwable = null
    try {
      f(resource)
    } catch {
      case NonFatal(e) =>
        exception = e
        throw e
    } finally {
      closeAndAddSuppressed(exception, resource)
    }
  }

  def loadInstance[T](objectName: String): T = {
    val runtimeMirror = universe.runtimeMirror(getClass.getClassLoader)
    val module = runtimeMirror.staticModule(objectName)
    val obj: universe.ModuleMirror = runtimeMirror.reflectModule(module)
    obj.instance.asInstanceOf[T]
  }

  private def closeAndAddSuppressed(e: Throwable, resource: AutoCloseable): Unit = {
    if (e != null) {
      try {
        resource.close()
      } catch {
        case NonFatal(suppressed) =>
          e.addSuppressed(suppressed)
      }
    } else {
      resource.close()
    }
  }

  /** If the provided `attempt` is a `Success[T]`, do nothing.
    * If it is a `Failure`, then log the contained exception as a side effect and carry on
    *
    * @param attempt
    * @param logger the logger onto which to log results
    * @tparam T
    * @return the original `attempt` with no alteration (everything happens as a side effect)
    */
  def logFailure[T](attempt: Try[T], logger: Logger): Try[T] =
    attempt match {
      case success @ Success(_) =>
        success

      case failure @ Failure(exception) =>
        logException(logger, exception)
        failure
    }

  def logException(logger: Logger, exception: Throwable): Unit = {
    logger.error(exceptionAsString(exception))
  }

  def exceptionAsString(exception: Throwable): String = {
    val sw = new StringWriter
    exception.printStackTrace(new PrintWriter(sw))
    sw.toString
  }

  def getDBDisposition(writeMode: WriteMode, hasMergeKeyDefined: Boolean): (String, String) = {
    val (createDisposition, writeDisposition) = (hasMergeKeyDefined, writeMode) match {
      case (true, wm) if wm == WriteMode.OVERWRITE || wm == WriteMode.APPEND =>
        ("CREATE_IF_NEEDED", "WRITE_TRUNCATE")
      case (_, WriteMode.OVERWRITE) =>
        ("CREATE_IF_NEEDED", "WRITE_TRUNCATE")
      case (_, WriteMode.APPEND) =>
        ("CREATE_IF_NEEDED", "WRITE_APPEND")
      case (_, WriteMode.ERROR_IF_EXISTS) =>
        ("CREATE_IF_NEEDED", "WRITE_EMPTY")
      case (_, WriteMode.IGNORE) =>
        ("CREATE_NEVER", "WRITE_EMPTY")
      case _ =>
        ("CREATE_IF_NEEDED", "WRITE_TRUNCATE")
    }
    (createDisposition, writeDisposition)
  }

  def subst(
    value: String,
    params: Map[String, String]
  ): String = {
    val paramsList = params.toList
    val paramKeys = paramsList.map { case (name, _) => name }
    val paramValues = paramsList.map { case (_, value) => value }

    val allParams = paramKeys.zip(paramValues)
    allParams.foldLeft(value) { case (acc, (p, v)) =>
      s"\\b($p)\\b".r.replaceAllIn(acc, v)
    }
  }

  def toMap(attributes: List[Attribute]): Map[String, Any] = {
    attributes.map { attribute =>
      attribute.attributes match {
        case Some(attributes) => attribute.name -> toMap(attributes)
        case None             => attribute.name -> attribute
      }
    }.toMap
  }
}
