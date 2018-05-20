/*
 * Copyright 2018 https://www.reactivedesignpatterns.com/ & http://rdp.reactiveplatform.xyz/
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

package chapter15.pattern.ask

import java.util.UUID

import akka.actor.Scheduler
import akka.pattern.AskTimeoutException
import akka.typed.AskPattern._
import akka.typed.ScalaDSL._
import akka.typed._
import akka.util.Timeout
import chapter15._

import scala.concurrent.Future
import scala.concurrent.duration._

object AskPattern {
  def withoutAskPattern(emailGateway: ActorRef[SendEmail]): Behavior[StartVerificationProcess] =
    ContextAware[MyCommands] { ctx ⇒
      val log = new BusLogging(ctx.system.eventStream, "VerificationProcessManager", getClass, ctx.system.logFilter)
      var statusMap = Map.empty[UUID, (String, ActorRef[VerificationProcessResponse])]
      val adapter = ctx.spawnAdapter((s: SendEmailResult) ⇒ MyEmailResult(s.correlationID, s.status, s.explanation))

      Static {
        case StartVerificationProcess(userEmail, replyTo) ⇒
          val corrID = UUID.randomUUID()
          val request = SendEmail("verification@example.com", List(userEmail), constructBody(userEmail, corrID), corrID, adapter)
          emailGateway ! request
          statusMap += corrID -> (userEmail, replyTo)
          ctx.schedule(5.seconds, ctx.self, MyEmailResult(corrID, StatusCode.Failed, Some("timeout")))
        case MyEmailResult(corrID, status, expl) ⇒
          statusMap.get(corrID) match {
            case None ⇒
              log.error("received SendEmailResult for unknown correlation ID {}", corrID)
            case Some((userEmail, replyTo)) ⇒
              status match {
                case StatusCode.OK ⇒
                  log.debug("successfully started the verification process for {}", userEmail)
                  replyTo ! VerificationProcessStarted(userEmail)
                case StatusCode.Failed ⇒
                  log.info("failed to start the verification process for {}: {}", userEmail, expl)
                  replyTo ! VerificationProcessFailed(userEmail)
              }
              statusMap -= corrID
          }
      }
    }.narrow[StartVerificationProcess]

  def withChildActor(emailGateway: ActorRef[SendEmail]): Behavior[StartVerificationProcess] =
    ContextAware { ctx: ActorContext[StartVerificationProcess] ⇒
      val log = new BusLogging(ctx.system.eventStream, "VerificationProcessManager", getClass, ctx.system.logFilter)

      Static {
        case StartVerificationProcess(userEmail, replyTo) ⇒
          val corrID = UUID.randomUUID()
          val childActor = ctx.spawnAnonymous(FullTotal[Result] {
            case Sig(ctx, PreStart) ⇒
              ctx.setReceiveTimeout(5.seconds, ReceiveTimeout)
              Same
            case Msg(_, ReceiveTimeout) ⇒
              log.warning("verification process initiation timed out for {}", userEmail)
              replyTo ! VerificationProcessFailed(userEmail)
              Stopped
            case Msg(_, SendEmailResult(`corrID`, StatusCode.OK, _)) ⇒
              log.debug("successfully started the verification process for {}", userEmail)
              replyTo ! VerificationProcessStarted(userEmail)
              Stopped
            case Msg(_, SendEmailResult(`corrID`, StatusCode.Failed, explanation)) ⇒
              log.info("failed to start the verification process for {}: {}", userEmail, explanation)
              replyTo ! VerificationProcessFailed(userEmail)
              Stopped
            case Msg(_, SendEmailResult(wrongID, _, _)) ⇒
              log.error("received wrong SendEmailResult for corrID {}", corrID)
              Same
          })
          val request = SendEmail("verification@example.com", List(userEmail), constructBody(userEmail, corrID), corrID, childActor)
          emailGateway ! request
      }
    }

  def withAskPattern(emailGateway: ActorRef[SendEmail]): Behavior[StartVerificationProcess] =
    ContextAware { ctx ⇒
      val log = new BusLogging(ctx.system.eventStream, "VerificationProcessManager", getClass, ctx.system.logFilter)
      implicit val timeout: Timeout = Timeout(5.seconds)
      import ctx.executionContext
      implicit val scheduler: Scheduler = ctx.system.scheduler

      Static {
        case StartVerificationProcess(userEmail, replyTo) ⇒
          val corrID = UUID.randomUUID()
          val response: Future[SendEmailResult] =
            emailGateway ? (SendEmail("verification@example.com", List(userEmail), constructBody(userEmail, corrID), corrID, _))
          response.map {
            case SendEmailResult(`corrID`, StatusCode.OK, _) ⇒
              log.debug("successfully started the verification process for {}", userEmail)
              VerificationProcessStarted(userEmail)
            case SendEmailResult(`corrID`, StatusCode.Failed, explanation) ⇒
              log.info("failed to start the verification process for {}: {}", userEmail, explanation)
              VerificationProcessFailed(userEmail)
            case SendEmailResult(wrongID, _, _) ⇒
              log.error("received wrong SendEmailResult for corrID {}", corrID)
              VerificationProcessFailed(userEmail)
          }.recover {
            case _: AskTimeoutException ⇒
              log.warning("verification process initiation timed out for {}", userEmail)
              VerificationProcessFailed(userEmail)
          }.foreach(result ⇒ replyTo ! result)
      }
    }

  private def constructBody(userEmail: String, corrID: UUID): String = ???

}
