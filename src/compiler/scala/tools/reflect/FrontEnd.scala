/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc. dba Akka
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools
package reflect

import scala.reflect.internal.util.Position

trait FrontEnd {
  object severity extends Enumeration
  class Severity(val id: Int) extends severity.Value {
    var count: Int = 0
    override def toString() = this match {
      case INFO => "INFO"
      case WARNING => "WARNING"
      case ERROR => "ERROR"
      case _ => "<unknown>"
    }
  }
  val INFO    = new Severity(0)
  val WARNING = new Severity(1)
  val ERROR   = new Severity(2)

  def hasErrors   = ERROR.count > 0
  def hasWarnings = WARNING.count > 0

  case class Info(pos: Position, msg: String, severity: Severity)
  val infos = new scala.collection.mutable.LinkedHashSet[Info]

  /** Handles incoming info */
  def log(pos: Position, msg: String, severity: Severity): Unit = {
    infos += Info(pos, msg, severity)
    severity.count += 1
    display(infos.last)
  }

  /** Displays incoming info */
  def display(info: Info): Unit

  /** Refreshes the UI */
  def flush(): Unit = {}

  /** Resets the reporter */
  def reset(): Unit = {
    INFO.count = 0
    WARNING.count = 0
    ERROR.count = 0
    infos.clear()
  }
}
