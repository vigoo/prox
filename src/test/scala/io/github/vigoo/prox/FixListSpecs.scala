package io.github.vigoo.prox

import org.specs2._
import shapeless._
import shapeless.test.illTyped
import FixList._
import org.specs2.matcher.Matcher

// scalastyle:off public.methods.have.type
// scalastyle:off public.member.have.type

class FixListSpecs extends Specification { def is = s2"""
  'FixList' is a fixed length list of fixed element types which is convertible
   to a 'HList'.

  The FixList should be typesafe
    regarding the Elem type               $elemTypeSafety
    regarding the list length             $lengthTypeSafety


  The FixList should be convertible to HList
    when it is empty                      $emptyAsHList
    when it is not empty                  $nonEmptyAsHList

  The FixList should provide way to
    prepend an element to empty list      $prependToEmpty
    prepend and element to non-empty list $prependToNonEmpty
    append list to empty list             $appendNonEmptyToEmpty
    append empty list to non-empty list   $appendEmptyToNonEmpty
    append two empty lists                $appendEmptyToEmpty
    append two non-empty lists            $appendNonEmptyLists
    get last element of non-empty lists   $lastNonEmptyList
    get last element of an empty list     $lastEmptyList
  """

  private def haveSameTypeAs[A, B](other: B)(implicit ev: A =:= B = null): Matcher[A] =
    ((_: A) => ev != null, "The two types does not match")

  private def elemTypeSafety = {
    val good: FixList[Int] = 1 :|: 2 :|: 3 :|: FixNil[Int]
    illTyped("""val bad: FixList[Int] = 1 :|: "2" :|: 3 :|: FixNil[Int]""")
    ok
  }

  def lengthTypeSafety = {
    (1 :|: 2 :|: 3 :|: FixNil[Int]) must haveSameTypeAs (5 :|: 6 :|: 7 :|: FixNil[Int])
    (1 :|: 2 :|: 3 :|: FixNil[Int]) must not(haveSameTypeAs (5 :|: 6 :|: 7 :|: 7 :|: FixNil[Int]))
  }

  def emptyAsHList = {
    FixNil[String].asHList === HNil
  }

  def nonEmptyAsHList = {
    ("a" :|: "b" :|: "c" :|: FixNil[String]).asHList === "a" :: "b" :: "c" :: HNil
  }

  val empty = FixNil[Int]
  val nonEmpty = 1 :|: 2 :|: 3 :|: FixNil[Int]

  def prependToEmpty = {
    (10 :|: empty).asHList === 10 :: HNil
  }

  def prependToNonEmpty = {
    (10 :|: nonEmpty).asHList === 10 :: 1 :: 2 :: 3 :: HNil
  }

  def appendNonEmptyToEmpty = {
    empty.append(nonEmpty).asHList === nonEmpty.asHList
  }

  def appendEmptyToNonEmpty = {
    nonEmpty.append(empty).asHList === nonEmpty.asHList
  }

  def appendEmptyToEmpty = {
    empty.append(empty).asHList === HNil
  }

  def appendNonEmptyLists = {
    nonEmpty.append(4 :|: 5 :|: FixNil[Int]).asHList === 1 :: 2 :: 3 :: 4 :: 5 :: HNil
  }

  def lastNonEmptyList = {
    nonEmpty.last === Some(3)
  }

  def lastEmptyList = {
    empty.last === None
  }
}
