package de.frosner.broccoli.services

import java.io.{ObjectOutputStream, _}
import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Named, Singleton}

import akka.actor._
import de.frosner.broccoli.models._
import de.frosner.broccoli.models.InstanceStatus.InstanceStatus
import de.frosner.broccoli.services.NomadService.{DeleteJob, GetServices, GetStatuses, StartJob}
import de.frosner.broccoli.util.Logging
import play.api.{Configuration, Play}
import play.api.libs.json.{JsArray, JsString, Json}
import play.api.libs.ws.WSClient

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}
import InstanceService._
import de.frosner.broccoli.conf
import play.Logger
import play.api.inject.ApplicationLifecycle

import scala.io.Source

@Singleton
class InstanceService @Inject()(templateService: TemplateService,
                                system: ActorSystem,
                                @Named("nomad-actor") nomadActor: ActorRef,
                                ws: WSClient,
                                configuration: Configuration,
                                lifecycle: ApplicationLifecycle) extends Actor with Logging {

  private val pollingFrequencySecondsString = configuration.getString(conf.POLLING_FREQUENCY_KEY)
  private val pollingFrequencySecondsTry = pollingFrequencySecondsString match {
    case Some(string) => Try(string.toInt).flatMap {
      int => if (int >= 1) Success(int) else Failure(new Exception())
    }
    case None => Success(conf.POLLING_FREQUENCY_DEFAULT)
  }
  if (pollingFrequencySecondsTry.isFailure) {
    Logger.error(s"Invalid ${conf.POLLING_FREQUENCY_KEY} specified: '${pollingFrequencySecondsString.get}'. Needs to be a positive integer.")
    System.exit(1)
  }
  private val pollingFrequencySeconds = pollingFrequencySecondsTry.get
  Logger.info(s"Nomad/Consul polling frequency set to $pollingFrequencySeconds seconds")
  private val cancellable: Cancellable = system.scheduler.schedule(
    initialDelay = Duration.Zero,
    interval = Duration.create(pollingFrequencySeconds, TimeUnit.SECONDS),
    receiver = nomadActor,
    message = GetStatuses
  )(
    system.dispatcher,
    self
  )

  private val instancesFilePath = configuration.getString(conf.INSTANCES_FILE_KEY).getOrElse(conf.INSTANCES_FILE_DEFAULT)
  private val instancesDirectory = new File(instancesFilePath)
  private val instancesFileLockPath = instancesFilePath + ".lock"

  val nomadJobPrefix = {
    val prefix = conf.getNomadJobPrefix(configuration)
    Logger.info(s"Using ${conf.NOMAD_JOB_PREFIX_KEY}=$prefix")
    prefix
  }

  implicit val executionContext = play.api.libs.concurrent.Execution.Implicits.defaultContext

  // TODO #21 filter instances based on prefix also on read? might depend also on #45
  @volatile
  private var instances: Map[String, Instance] = {
    Logger.info(s"Locking $instancesFilePath ($instancesFileLockPath)")
    val lock = new File(instancesFileLockPath)
    if (lock.createNewFile()) {
      sys.addShutdownHook{
        Logger.info(s"Releasing lock on '$instancesFilePath' ('$instancesFileLockPath')")
        lock.delete()
      }
      Logger.debug(s"Looking for instances in '$instancesFilePath'.")
      if (instancesDirectory.canRead && instancesDirectory.isDirectory) {
        val instanceFiles = instancesDirectory.listFiles(new FileFilter {
          override def accept(pathname: File): Boolean = pathname.getPath.endsWith(".json")
        })
        val maybeInstances = instanceFiles.flatMap { instanceFile =>
          val maybeInstance = InstanceService.loadInstance(new FileInputStream(instanceFile))
          if (maybeInstance.isFailure) {
            val throwable = maybeInstance.failed.get
            Logger.error(s"Error parsing '$instanceFile': $throwable")
          } else {
            if (maybeInstance.get.id != instanceFile.getName.stripSuffix(".json")) {
              Logger.warn(s"Successfully parsed '$instanceFile' but the file name did not match the instance ID.")
            } else {
              Logger.info(s"Successfully parsed '$instanceFile'.")
            }
          }
          maybeInstance.toOption
        }
        maybeInstances.map(instance => (instance.id, instance)).toMap
      } else {
        if (instancesDirectory.mkdir()) {
          Logger.info(s"Instance directory '$instancesFilePath' not found. Initializing with an empty instance collection.")
          Map.empty
        } else {
          val error = s"Instance directory '$instancesFilePath' not found but cannot be created. Exiting."
          Logger.error(error)
          System.exit(1)
          throw new IllegalStateException(error)
        }
      }
    } else {
      val error = s"Cannot lock $instancesFilePath. Is there another Broccoli instance running?"
      Logger.error(error)
      System.exit(1)
      throw new IllegalStateException(error)
    }
  }

  def receive = {
    case GetInstances => sender ! getInstances
    case GetInstance(id) => sender ! getInstance(id)
    case NewInstance(instanceCreation) => sender() ! addInstance(instanceCreation)
    case updateInstanceInfo: UpdateInstance => sender() ! updateInstance(updateInstanceInfo)
    case DeleteInstance(id) => sender ! deleteInstance(id)
    case NomadStatuses(statuses) => updateStatusesBasedOnNomad(statuses)
    case ConsulServices(id, services) => updateServicesBasedOnNomad(id, services)
    case NomadNotReachable => setAllStatusesToUnknown()
  }

  private[this] def setAllStatusesToUnknown() = {
    instances.foreach {
      case (id, instance) => instance.status = InstanceStatus.Unknown
    }
  }

  private[this] def updateStatusesBasedOnNomad(statuses: Map[String, InstanceStatus]) = {
    instances.foreach { case (id, instance) =>
      statuses.get(id) match {
        case Some(nomadStatus) =>
          instance.status = nomadStatus
          nomadActor ! GetServices(id)
        case None =>
          instance.status = InstanceStatus.Stopped
          instance.services = Map.empty
      }
    }
  }

  private[this] def updateServicesBasedOnNomad(jobId: String, services: Iterable[Service]) = {
    instances.get(jobId) match {
      case Some(instance) => instance.services = services.map(service => (service.name, service)).toMap
      case None => Logger.error(s"Received services associated to non-existing job $jobId")
    }
  }

  private[this] def getInstances: Iterable[Instance] = {
    instances.values.filter(_.id.startsWith(nomadJobPrefix))
  }

  private[this] def getInstance(id: String): Option[Instance] = {
    instances.get(id).filter(_.id.startsWith(nomadJobPrefix))
  }

  private[this] def addInstance(instanceCreation: InstanceCreation): Try[Instance] = {
    Logger.info(s"Request received to create new instance: $instanceCreation")
    val maybeId = instanceCreation.parameters.get("id") // FIXME requires ID to be defined inside the parameter values
    val templateId = instanceCreation.templateId
    maybeId.map { id =>
      if (id.startsWith(nomadJobPrefix)) {
        if (instances.contains(id)) {
          Failure(newExceptionWithWarning(new IllegalArgumentException(s"There is already an instance having the ID $id")))
        } else {
          val potentialTemplate = templateService.template(templateId)
          potentialTemplate.map { template =>
            val maybeNewInstance = Try(Instance(id, template, instanceCreation.parameters, InstanceStatus.Stopped, Map.empty))
            maybeNewInstance.map { newInstance =>
              instances = instances.updated(id, newInstance)
              InstanceService.persistInstanceInFile(newInstance, instancesDirectory)
              newInstance
            }
          }.getOrElse(Failure(newExceptionWithWarning(new IllegalArgumentException(s"Template $templateId does not exist."))))
        }
      } else {
        Failure(newExceptionWithWarning(new IllegalArgumentException(s"ID '$id' does not have the required prefix '$nomadJobPrefix'.")))
      }
    }.getOrElse(Failure(newExceptionWithWarning(new IllegalArgumentException("No ID specified"))))
  }

  private[this] def updateInstance(updateInstance: UpdateInstance): Option[Try[Instance]] = {
    val updateInstanceId = updateInstance.id
    if (updateInstanceId.startsWith(nomadJobPrefix)) {
      val maybeInstance = instances.get(updateInstanceId)
      maybeInstance.map { instance =>
        val instanceWithPotentiallyUpdatedTemplateAndParameterValues: Try[Instance] = if (updateInstance.templateSelector.isDefined) {
          val newTemplateId = updateInstance.templateSelector.get.newTemplateId
          val newTemplate = templateService.template(newTemplateId)
          newTemplate.map { template =>
            // Requested template exists, update the template
            if (updateInstance.parameterValuesUpdater.isDefined) {
              // New parameter values are specified
              val newParameterValues = updateInstance.parameterValuesUpdater.get.newParameterValues
              instance.updateTemplate(template, newParameterValues)
            } else {
              // Just use the old parameter values
              instance.updateTemplate(template, instance.parameterValues)
            }
          }.getOrElse {
            // New template does not exist
            Failure(TemplateNotFoundException(newTemplateId))
          }
        } else {
          // No template update required
          if (updateInstance.parameterValuesUpdater.isDefined) {
            // Just update the parameter values
            val newParameterValues = updateInstance.parameterValuesUpdater.get.newParameterValues
            instance.updateParameterValues(newParameterValues)
          } else {
            // Neither template update nor parameter value update required
            Success(instance)
          }
        }
        val updatedInstance = instanceWithPotentiallyUpdatedTemplateAndParameterValues.map { instance =>
          updateInstance.statusUpdater.map {
            // Update the instance status
            case StatusUpdater(InstanceStatus.Running) =>
              nomadActor.tell(StartJob(instance.templateJson), self)
              Success(instance)
            case StatusUpdater(InstanceStatus.Stopped) =>
              nomadActor.tell(DeleteJob(instance.id), self)
              Success(instance)
            case other =>
              Failure(new IllegalArgumentException(s"Unsupported status change received: $other"))
          }.getOrElse {
            // Don't update the instance status
            Success(instance)
          }
        }.flatten

        updatedInstance match {
          case Failure(throwable) => Logger.error(s"Error updating instance: $throwable")
          case Success(changedInstance) => Logger.debug(s"Successfully applied an update to $changedInstance")
        }

        InstanceService.persistInstanceInFile(instance, instancesDirectory)
        updatedInstance
      }
    } else {
      Some(Failure(newExceptionWithWarning(new IllegalArgumentException(s"ID '$updateInstanceId' does not have the required prefix '$nomadJobPrefix'."))))
    }
  }

  private[this] def deleteInstance(id: String): Boolean = {
    if (id.startsWith(nomadJobPrefix)) {
      updateInstance(UpdateInstance(
        id = id,
        statusUpdater = Some(StatusUpdater(InstanceStatus.Stopped)),
        parameterValuesUpdater = None,
        templateSelector = None
      ))
      if (instances.contains(id)) {
        val deletedInstance = instances(id)
        if (InstanceService.unpersistInstanceInFile(deletedInstance, instancesDirectory)) {
          instances = instances - id
          true
        } else {
          Logger.error(s"Could not delete instance '$id'.")
          false
        }
      } else {
        false
      }
    } else {
      false
    }
  }

}

object InstanceService {

  case object GetInstances

  case class GetInstance(id: String)

  case class NewInstance(instanceCreation: InstanceCreation)

  case class StatusUpdater(newStatus: InstanceStatus)

  case class ParameterValuesUpdater(newParameterValues: Map[String, String])

  case class TemplateSelector(newTemplateId: String)

  case class UpdateInstance(id: String,
                            statusUpdater: Option[StatusUpdater],
                            parameterValuesUpdater: Option[ParameterValuesUpdater],
                            templateSelector: Option[TemplateSelector])

  case class DeleteInstance(id: String)

  case class NomadStatuses(statuses: Map[String, InstanceStatus])

  case class ConsulServices(jobId: String, jobServices: Iterable[Service])

  case object NomadNotReachable

  def persistInstance(instance: Instance, output: OutputStream): Instance = {
    import Instance.instancePersistenceWrites
    val printStream = new PrintStream(output)
    printStream.append(Json.toJson(instance).toString())
    printStream.close()
    instance
  }

  def instanceToFileName(instance: Instance) = instance.id + ".json"

  def persistInstanceInFile(instance: Instance, instancesDirectory: File) = {
    persistInstance(instance, new FileOutputStream(new File(instancesDirectory, instanceToFileName(instance))))
  }

  def unpersistInstanceInFile(instance: Instance, instancesDirectory: File): Boolean = {
    val instanceFile = new File(instancesDirectory, instanceToFileName(instance))
    instanceFile.delete()
  }

  def loadInstance(input: InputStream): Try[Instance] = {
    val source = Source.fromInputStream(input)
    val instance = Try(Json.parse(input).as[Instance])
    source.close()
    instance
  }

}
