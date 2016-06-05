package de.frosner.broccoli.controllers

import javax.inject.Inject

import de.frosner.broccoli.models.{Instance}
import Instance.{instanceReads, instanceWrites}
import de.frosner.broccoli.services.InstanceService

import play.api.libs.json.Json
import play.api.mvc.{Action, Controller}

class InstanceController @Inject() (instanceService: InstanceService) extends Controller {

  implicit val context = play.api.libs.concurrent.Execution.Implicits.defaultContext

  def list(templateId: Option[String]) = Action.async {
    val maybeFilteredInstances = instanceService.instances.map(
      instances => templateId.map(
        id => instances.filter(_.template.id == id)
      ).getOrElse(instances)
    )
    maybeFilteredInstances.map(instances => Ok(Json.toJson(instances)))
  }

  def show(id: String) = Action.async {
    instanceService.instance(id).map(_.map(instance => Ok(Json.toJson(instance))).getOrElse(NotFound))
  }

}
