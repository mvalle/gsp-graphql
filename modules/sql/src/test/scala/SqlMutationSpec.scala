// Copyright (c) 2016-2020 Association of Universities for Research in Astronomy, Inc. (AURA)
// For license information see LICENSE or https://opensource.org/licenses/BSD-3-Clause

package edu.gemini.grackle.sql.test

import cats.effect.IO
import io.circe.Json
import org.scalatest.funsuite.AnyFunSuite
import cats.effect.unsafe.implicits.global

import edu.gemini.grackle._
import syntax._

import grackle.test.GraphQLResponseTests.assertWeaklyEqual

trait SqlMutationSpec extends AnyFunSuite {

  def mapping: QueryExecutor[IO, Json]

  def check(query: String, expected: Json) =
    assertWeaklyEqual(mapping.compileAndRun(query).unsafeRunSync(), expected)

  // In this test the query is fully elaborated prior to execution.
  test("simple update") {
    check("""
        mutation {
          updatePopulation(id: 2, population: 12345) {
            name
            population
            country {
              name
            }
          }
        }
      """,
      json"""
        {
          "data" : {
            "updatePopulation" : {
              "name" : "Qandahar",
              "population" : 12345,
              "country" : {
                "name" : "Afghanistan"
              }
            }
          }
        }
      """
    )
  }

  // In this test the query must be elaborated *after* execution because the ID of the inserted
  // city isn't known until then.
  test("insert") {
    check("""
        mutation {
          createCity(name: "Wiggum", countryCode: "USA", population: 789) {
            name
            population
            country {
              name
            }
          }
        }
      """,
      json"""
        {
          "data" : {
            "createCity" : {
              "name" : "Wiggum",
              "population" : 789,
              "country" : {
                "name" : "United States"
              }
            }
          }
        }
      """
    )
  }

}
