// Copyright (C) 2015 Duncan DeVore @ironfish
package org.atheneum.common

object Protocol {

  trait Msg

  final case class ErrorMsg(errors: List[String]) extends Msg

  trait Cmd extends Msg {
    def id: String
    def expVer: Long
  }

  trait Evt extends Msg {
    def id: String
    def ver: Long
  }
}
