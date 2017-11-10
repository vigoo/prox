package io.github.vigoo.prox

import shapeless._

import scala.language.higherKinds

sealed trait FixList[Elem]  {
  type HListType <: HList
  def asHList: HListType

  type Self <: FixList[Elem]
  type PrependOne <: FixList[Elem]
  type Concat[Other <: FixList[Elem]] <: FixList[Elem]

  def :|:(newHead: Elem): Self#PrependOne
  def append[L <: FixList[Elem]](newPrefix: L): Concat[L]
  def last: Option[Elem]
}

final case class FixCons[Elem, Tail <: FixList[Elem]](head: Elem, tail: Tail) extends FixList[Elem] {

  override type Self = FixCons[Elem, Tail]
  override type PrependOne = FixCons[Elem, Self]
  override type Concat[Other <: FixList[Elem]] = FixCons[Elem, Tail#Concat[Other]]
  override type HListType = Elem :: Tail#HListType

  override def :|:(newHead: Elem): FixCons[Elem, FixCons[Elem, Tail]] =
    FixCons(newHead, this)

  override def append[L <: FixList[Elem]](newPrefix: L) =
    FixCons(head, tail.append(newPrefix))

  override def asHList: HListType = head :: tail.asHList

  override def last: Option[Elem] =
    if (tail.asHList == HNil) {
      Some(head)
    } else {
      tail.last
    }
}

final class FixNil[Elem]() extends FixList[Elem] {

  override type Self = FixNil[Elem]
  override type PrependOne = FixCons[Elem, FixNil[Elem]]
  override type Concat[Other <: FixList[Elem]] = Other
  override type HListType = HNil

  override def :|:(head: Elem): FixCons[Elem, FixNil[Elem]] =
    FixCons(head, FixNil[Elem])

  override def append[L0 <: FixList[Elem]](l0: L0): L0 =
    l0

  override def asHList: HNil = HNil

  override def last: Option[Elem] = None
}

object FixNil {
  def apply[Elem]: FixNil[Elem] = new FixNil[Elem]
}

object FixList {
  type :|:[Elem, Tail <: FixList[Elem]] = FixCons[Elem, Tail]
  type Concatenated[Elem, L1 <: FixList[Elem], L2 <: FixList[Elem]] = L1#Concat[L2]
}
