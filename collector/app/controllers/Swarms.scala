package controllers

import com.amazonaws.services.simpleworkflow.model.StartWorkflowExecutionRequest
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._
import play.core.parsers.FormUrlEncodedParser
import swarmize.{Swarm, SwarmSubmissionValidator}
import swarmize.aws.{AWS, SimpleWorkflowConfig}
import swarmize.json.SubmittedData

import scala.util.Try
import scala.util.control.NonFatal

object Swarms extends Controller {

  def show(token: String) = Action {
    val config = Swarm.findByToken(token)

    config.map { c =>
      val msg =
        s"""
          |Swarm name: ${c.name}
          |Swarm description: ${c.description}
          |Status: ${c.status}
          |
          |Fields:
          |${c.fields.map(_.description).mkString("\n")}
          |
          |Dervied Fields:
          |${c.derivedFields.map(_.description).mkString("\n")}
          |
          |Schema:
          |${Json.prettyPrint(c.definition.toJson)}
        """.stripMargin

      Ok(msg)
    } getOrElse {
      NotFound(s"Unknown token: $token")
    }
  }


  private def formFieldsToJson(fields: Map[String, Seq[String]]): JsValue = {
    val jsonValues = fields.mapValues {
      _.toList match {
        case Nil => JsNull
        case single :: Nil => JsString(single)
        case other => JsArray(other map JsString)
      }
    }

    JsObject(jsonValues.toSeq)
  }

  def submit(token: String) = Action(parse.tolerantText) { request =>
    Swarm.findByToken(token).map { config =>
      val json = Try(Json.parse(request.body))
        .orElse(Try(FormUrlEncodedParser.parse(request.body)).map(formFieldsToJson))

      json.map(doSubmitJson(config, _)) getOrElse
        BadRequest("Either submit text/json, application/json or application/x-www-form-urlencoded")

    } getOrElse {
      NotFound(s"Unknown swarm token: $token")
    }
 }

  def submitJson(token: String) = Action(parse.tolerantJson) { request =>
    Swarm.findByToken(token).map { swarm =>
      doSubmitJson(swarm, request.body)
    } getOrElse {
      NotFound(s"Unknown swarm token: $token")
    }
  }


  private def addTimestampIfNotPresent(json: JsObject): JsObject = {
    if (json.keys contains "timestamp") json
    else json ++ Json.obj("timestamp" -> DateTime.now.toString(ISODateTimeFormat.dateTime()))
  }

  private def doSubmitJson(swarm: Swarm, data: JsValue): Result = try {
    if (swarm.isClosed) {
      Gone("This swarm is now closed")
    } else {
      val dataObj = SwarmSubmissionValidator.validated(swarm, data.as[JsObject])

      val fullObject = SubmittedData.wrap(addTimestampIfNotPresent(dataObj), swarm)

      val msg = s"submission to ${swarm.name}:\n${Json.prettyPrint(fullObject.toJson)}\n"

      AWS.swf.startWorkflowExecution(
        new StartWorkflowExecutionRequest()
          .withDomain(SimpleWorkflowConfig.domain)
          .withInput(Json.toJson(fullObject).toString())
          .withWorkflowId(fullObject.submissionId)
          .withWorkflowType(SimpleWorkflowConfig.workflowType)
          .withTagList(fullObject.swarmToken)
      )

      Logger.info(msg)
      Ok(fullObject.toJson)
    }
  } catch {
    case NonFatal(e) =>
      Logger.warn("submission failed", e)
      BadRequest(e.getMessage + "\n")
  }

}
