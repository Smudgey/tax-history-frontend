/*
 * Copyright 2017 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package services.http

import com.codahale.metrics.Timer
import com.kenshoo.play.metrics.Metrics
import models.TaxSummary
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.mock.MockitoSugar
import play.api.http.Status._
import play.api.libs.json.Json
import services._
import support.BaseSpec
import uk.gov.hmrc.domain.{Generator, Nino}
import uk.gov.hmrc.play.http._

import scala.io.Source
import scala.util.Random


class TaiServiceSpec extends BaseSpec {

  def exampleTaxSummaryDetailsJson = Source.fromInputStream(getClass.getResourceAsStream("/tai/taxSummaryDetails/AA111111.json"), "UTF-8").mkString
  def exampleTaxSummaryDetailsJsonTaxCodeEndsN = Source.fromInputStream(getClass.getResourceAsStream("/tai/taxSummaryDetails/AA111111A.json"), "UTF-8").mkString
  def exampleTaxSummaryDetailsJsonTaxCodeEndsM = Source.fromInputStream(getClass.getResourceAsStream("/tai/taxSummaryDetails/AA111112A.json"), "UTF-8").mkString
  def exampleTaxSummaryDetailsJsonBothCompanyBenefits = Source.fromInputStream(getClass.getResourceAsStream("/tai/taxSummaryDetails/AA111113A.json"), "UTF-8").mkString
  def exampleTaxSummaryDetailsJsonCompanyBenefitsCarOnly = Source.fromInputStream(getClass.getResourceAsStream("/tai/taxSummaryDetails/AA111114A.json"), "UTF-8").mkString
  def exampleTaxSummaryDetailsJsonCompanyBenefitsMedicalOnly = Source.fromInputStream(getClass.getResourceAsStream("/tai/taxSummaryDetails/AA111115A.json"), "UTF-8").mkString



  val fakeNino = Nino(new Generator(new Random()).nextNino.nino)
  

  trait SpecSetup {
    def httpResponse: HttpResponse
    def simulateTaiServiceIsDown: Boolean

    val taxSummaryDetails = exampleTaxSummaryDetailsJson
    val jsonTaxSummaryDetails = Json.parse(taxSummaryDetails)
    val anException = new RuntimeException("Any")

    lazy val (service, metrics, timer) = {

      val fakeSimpleHttp = {
        if(simulateTaiServiceIsDown) new FakeSimpleHttp(Right(anException))
        else new FakeSimpleHttp(Left(httpResponse))
      }

      val timer = MockitoSugar.mock[Timer.Context]

      lazy val taiService: TaiService = new TaiService(fakeSimpleHttp, MockitoSugar.mock[Metrics]) {

        override val metricsOperator: MetricsOperator = MockitoSugar.mock[MetricsOperator]
        when(metricsOperator.startTimer(any())) thenReturn timer
      }

      (taiService, taiService.metricsOperator, timer)
    }
  }

  "Calling TaiService.taxSummary" should {

    trait LocalSetup extends SpecSetup {
      val metricId = "get-tax-summary"
    }

    "return a TaxSummarySuccessResponse containing a TaxSummaryDetails object when called with an existing nino and year" in new LocalSetup {

      override lazy val simulateTaiServiceIsDown = false
      override lazy val httpResponse = HttpResponse(OK, Some(jsonTaxSummaryDetails))

      val r = service.taxSummary(fakeNino, 2014)

      await(r) shouldBe TaxSummarySuccessResponse(TaxSummary(jsonTaxSummaryDetails))
      verify(metrics, times(1)).startTimer(metricId)
      verify(metrics, times(1)).incrementSuccessCounter(metricId)
      verify(timer, times(1)).stop()
    }

    "return TaxSummaryUnexpectedResponse when an unexpected status is returned" in new LocalSetup {

      override lazy val simulateTaiServiceIsDown = false
      val seeOtherResponse = HttpResponse(SEE_OTHER)
      override lazy val httpResponse = seeOtherResponse  //For example

      val r = service.taxSummary(fakeNino, 2014)

      await(r) shouldBe TaxSummaryUnexpectedResponse(seeOtherResponse)
      verify(metrics, times(1)).startTimer(metricId)
      verify(metrics, times(1)).incrementFailedCounter(metricId)
      verify(timer, times(1)).stop()
    }

    "return TaxSummaryNotFoundResponse when called with a nino that causes a 404 error" in new LocalSetup {

      override lazy val simulateTaiServiceIsDown = false
      override lazy val httpResponse = HttpResponse(NOT_FOUND)

      val r = service.taxSummary(fakeNino, 2014)

      await(r) shouldBe TaxSummaryNotFoundResponse
      verify(metrics, times(1)).startTimer(metricId)
      verify(metrics, times(1)).incrementFailedCounter(metricId)
      verify(timer, times(1)).stop()
    }

    "return TaxSummaryErrorResponse when called and service is down" in new LocalSetup {

      override lazy val simulateTaiServiceIsDown = true
      override lazy val httpResponse = ???

      val r = service.taxSummary(fakeNino, 2014)

      await(r) shouldBe TaxSummaryErrorResponse(anException)
      verify(metrics, times(1)).startTimer(metricId)
      verify(metrics, times(1)).incrementFailedCounter(metricId)
      verify(timer, times(1)).stop()
    }
  }
}
