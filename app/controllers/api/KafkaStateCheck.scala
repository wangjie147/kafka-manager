/**
 * Copyright 2015 Yahoo Inc. Licensed under the Apache License, Version 2.0
 * See accompanying LICENSE file.
 */

package controllers.api

import controllers.KafkaManagerContext
import features.ApplicationFeatures
import kafka.manager.model.ActorModel.{KMClusterList, TopicIdentity, TopicPartitionIdentity}
import models.navigation.Menus
import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.Future
import org.json4s.jackson.Serialization
import scala.collection.immutable.ListMap

/**
 * @author jisookim0513
 */

class KafkaStateCheck (val messagesApi: MessagesApi, val kafkaManagerContext: KafkaManagerContext)
                      (implicit af: ApplicationFeatures, menus: Menus) extends Controller with I18nSupport {

  import play.api.libs.concurrent.Execution.Implicits.defaultContext

  private[this] val kafkaManager = kafkaManagerContext.getKafkaManager

  def brokers(c: String) = Action.async { implicit request =>
    kafkaManager.getBrokerList(c).map { errorOrBrokerList =>
      errorOrBrokerList.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        brokerList => Ok(Json.obj("brokers" -> brokerList.list.map(bi => bi.id).sorted))
      )
    }
  }

  def topics(c: String) = Action.async { implicit request =>
    kafkaManager.getTopicList(c).map { errorOrTopicList =>
      errorOrTopicList.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        topicList => Ok(Json.obj("topics" -> topicList.list.sorted))
      )
    }
  }

  def topicIdentities(c: String) = Action.async { implicit request =>
    kafkaManager.getTopicListExtended(c).map { errorOrTopicList =>
      errorOrTopicList.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        topicIdentityList => Ok(getTopicIdentitiesListJson(topicIdentityList.list))
      )
    }
  }

  def getTopicIdentitiesListJson(topicIdentities: IndexedSeq[(String, Option[TopicIdentity])]) = {
    implicit val formats = org.json4s.DefaultFormats
    Serialization.write("topicIdentities" -> (for {
      (tn, tiOpt) <- topicIdentities
      ti <- tiOpt
    } yield ListMap("topic" -> tn, "partitions" -> ti.partitions,
        "numBrokers" -> ti.numBrokers,
        "brokersSpreadPercentage" -> ti.brokersSpreadPercentage,
        "brokersSkewPercentage" -> ti.brokersSkewPercentage,
        "replicationFactor" -> ti.replicationFactor,
        "partitionIdentities" -> getPartitionIdentitiesMap(ti.partitionsIdentity))
      ))
  }

    def getPartitionIdentitiesMap(partitionsIdentity: Map[Int,TopicPartitionIdentity]) = {
      for {
        (pn, tpi) <- ListMap(partitionsIdentity.toSeq.sortBy(_._1):_*)
      } yield ListMap("partNum" -> pn,  "isr" -> tpi.isr,
        "isPreferredLeader" -> tpi.isPreferredLeader,
        "isUnderReplicated" -> tpi.isUnderReplicated)
    }

  def clusters(status: Option[String]) = Action.async { implicit request =>
    kafkaManager.getClusterList.map { errorOrClusterList =>
      errorOrClusterList.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        clusterList => Ok(getClusterListJson(clusterList, status))
      )
    }
  }

  def getClusterListJson(clusterList: KMClusterList, status: Option[String]) = {
    val active = clusterList.active.map(cc => Map("name" -> cc.name, "status" -> "active"))
    val pending = clusterList.pending.map(cc => Map("name" -> cc.name, "status" -> "pending"))

    status match {
      case Some("active") => Json.obj("clusters" -> active.sortBy(_("name")))
      case Some("pending") => Json.obj("clusters" -> pending.sortBy(_("name")))
      case _ => Json.obj("clusters" -> (active ++ pending).sortBy(_("name")))
    }
  }

  def underReplicatedPartitions(c: String, t: String) = Action.async { implicit request =>
    kafkaManager.getTopicIdentity(c, t).map { errorOrTopicIdentity =>
      errorOrTopicIdentity.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        topicIdentity => Ok(Json.obj("topic" -> t, "underReplicatedPartitions" -> topicIdentity.partitionsIdentity.filter(_._2.isUnderReplicated).map{case (num, pi) => pi.partNum}))
      )
    }
  }

  def unavailablePartitions(c: String, t: String) = Action.async { implicit request =>
    kafkaManager.getTopicIdentity(c, t).map { errorOrTopicIdentity =>
      errorOrTopicIdentity.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        topicIdentity => Ok(Json.obj("topic" -> t, "unavailablePartitions" -> topicIdentity.partitionsIdentity.filter(_._2.isr.isEmpty).map { case (num, pi) => pi.partNum })))
    }
  }

  def topicSummaryAction(cluster: String, consumer: String, topic: String, consumerType: String) = Action.async { implicit request =>
    getTopicSummary(cluster, consumer, topic, consumerType).map { errorOrTopicSummary =>
      errorOrTopicSummary.fold(
        error => BadRequest(Json.obj("msg" -> error.msg)),
        topicSummary => {
          Ok(topicSummary)
        })
    }
  }

  def getTopicSummary(cluster: String, consumer: String, topic: String, consumerType: String) = {
    kafkaManager.getConsumedTopicState(cluster, consumer, topic, consumerType).map { errorOrTopicSummary =>
      errorOrTopicSummary.map(
        topicSummary => {
          Json.obj("totalLag" -> topicSummary.totalLag, "percentageCovered" -> topicSummary.percentageCovered)
        })
    }
  }

  def groupSummaryAction(cluster: String, consumer: String, consumerType: String) = Action.async { implicit request =>
    kafkaManager.getConsumerIdentity(cluster, consumer, consumerType).flatMap { errorOrConsumedTopicSummary =>
      errorOrConsumedTopicSummary.fold(
        error =>
          Future.successful(BadRequest(Json.obj("msg" -> error.msg))),
        consumedTopicSummary => getGroupSummary(cluster, consumer, consumedTopicSummary.topicMap.keys, consumerType).map { topics =>
          Ok(JsObject(topics))
        })
    }
  }

  def getGroupSummary(cluster: String, consumer: String, groups: Iterable[String], consumerType: String): Future[Map[String, JsObject]] = {
    val cosumdTopicSummary: List[Future[(String, JsObject)]] = groups.toList.map { group =>
      getTopicSummary(cluster, consumer, group, consumerType)
        .map(topicSummary => group -> topicSummary.getOrElse(Json.obj()))
    }
    Future.sequence(cosumdTopicSummary).map(_.toMap)
  }
}
