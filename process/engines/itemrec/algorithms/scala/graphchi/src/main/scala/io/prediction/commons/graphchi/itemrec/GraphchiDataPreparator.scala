package io.prediction.commons.graphchi.itemrec

import io.prediction.commons.Config
import io.prediction.commons.appdata.{ Item, U2IAction, User }

import grizzled.slf4j.Logger
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.io.BufferedWriter
import scala.io.Source

import com.twitter.scalding.Args

object GraphChiDataPreparator {

  /* constants */
  final val ACTION_RATE = "rate"
  final val ACTION_LIKE = "like"
  final val ACTION_DISLIKE = "dislike"
  final val ACTION_VIEW = "view"
  final val ACTION_CONVERSION = "conversion"

  // When there are conflicting actions, e.g. a user gives an item a rating 5 but later dislikes it, 
  // determine which action will be considered as final preference.
  final val CONFLICT_LATEST: String = "latest" // use latest action
  final val CONFLICT_HIGHEST: String = "highest" // use the one with highest score
  final val CONFLICT_LOWEST: String = "lowest" // use the one with lowest score

  /* global */
  val logger = Logger(GraphChiDataPreparator.getClass)

  println(logger.isInfoEnabled)

  val commonsConfig = new Config

  // argument of this job
  case class JobArg(
    val outputDir: String,
    val appid: Int,
    val evalid: Option[Int],
    val itypes: Option[List[String]],
    val viewParam: Option[Int],
    val likeParam: Option[Int],
    val dislikeParam: Option[Int],
    val conversionParam: Option[Int],
    val conflictParam: String,
    val matrixMarket: Boolean)

  def main(cmdArgs: Array[String]) {

    println("Running data preparator for GraphChi...")
    println(cmdArgs.mkString(","))

    /* get arg */
    val args = Args(cmdArgs)

    val outputDirArg = args("outputDir")
    val appidArg = args("appid").toInt
    val evalidArg = args.optional("evalid") map (x => x.toInt)
    val OFFLINE_EVAL = (evalidArg != None) // offline eval mode

    val preItypesArg = args.list("itypes")
    val itypesArg: Option[List[String]] = if (preItypesArg.mkString(",").length == 0) None else Option(preItypesArg)

    // determine how to map actions to rating values
    def getActionParam(name: String): Option[Int] = {
      val actionParam: Option[Int] = args(name) match {
        case "ignore" => None
        case x => Some(x.toInt)
      }
      actionParam
    }

    val viewParamArg: Option[Int] = getActionParam("viewParam")
    val likeParamArg: Option[Int] = getActionParam("likeParam")
    val dislikeParamArg: Option[Int] = getActionParam("dislikeParam")
    val conversionParamArg: Option[Int] = getActionParam("conversionParam")

    val conflictParamArg: String = args("conflictParam")

    // check if the conflictParam is valid
    require(List(CONFLICT_LATEST, CONFLICT_HIGHEST, CONFLICT_LOWEST).contains(conflictParamArg), "conflict param " + conflictParamArg + " is not valid.")

    // write data in matrix market format
    val matrixMarketArg: Boolean = args.optional("matrixMarket").map(x => x.toBoolean).getOrElse(true)

    val arg = JobArg(
      outputDir = outputDirArg,
      appid = appidArg,
      evalid = evalidArg,
      itypes = itypesArg,
      viewParam = viewParamArg,
      likeParam = likeParamArg,
      dislikeParam = dislikeParamArg,
      conversionParam = conversionParamArg,
      conflictParam = conflictParamArg,
      matrixMarket = matrixMarketArg
    )

    /* run job */
    dataPrep(arg)
    cleanup(arg)

  }

  case class RatingData(
    uid: Int,
    iid: Int,
    rating: Int,
    t: Long)

  def dataPrep(arg: JobArg) = {

    // NOTE: if OFFLINE_EVAL, read from training set, and use evalid as appid when read Items and U2iActions
    val OFFLINE_EVAL = (arg.evalid != None)

    val usersDb = if (!OFFLINE_EVAL)
      commonsConfig.getAppdataUsers
    else
      commonsConfig.getAppdataTrainingUsers

    val itemsDb = if (!OFFLINE_EVAL)
      commonsConfig.getAppdataItems
    else
      commonsConfig.getAppdataTrainingItems

    val u2iDb = if (!OFFLINE_EVAL)
      commonsConfig.getAppdataU2IActions
    else
      commonsConfig.getAppdataTrainingU2IActions

    val appid = if (OFFLINE_EVAL) arg.evalid.get else arg.appid

    // create outputDir if doesn't exist yet.
    val outputDir = new File(arg.outputDir)
    outputDir.mkdirs()

    /* write user index */
    val usersIndexWriter = new BufferedWriter(new FileWriter(new File(arg.outputDir + "usersIndex.tsv")))
    // TODO sort by ID when read from Mongo (although not needed for funtionality)
    // convert to Map for later lookup
    // assuming number of users can be fit into memory.
    val usersIndex: Map[String, Int] = usersDb.getByAppid(appid).map(_.id).zipWithIndex
      .map { case (uid, index) => (uid, index + 1) }.toMap // +1 to make it starting from 1

    // TODO: output with key order?
    usersIndex.foreach {
      case (uid, uindex) =>
        usersIndexWriter.write(s"${uindex}\t${uid}\n")
    }
    usersIndexWriter.close()

    /* write item index */
    val itemsIndexWriter = new BufferedWriter(new FileWriter(new File(arg.outputDir + "itemsIndex.tsv")))
    // TODO sort by ID when read from Mongo (although not needed for funtionality)
    val itemsIndex: Map[String, Int] = arg.itypes.map { itypes =>
      itemsDb.getByAppidAndItypes(appid, itypes)
    }.getOrElse {
      itemsDb.getByAppid(appid)
    }.map(_.id)
      .zipWithIndex
      .map { case (iid, index) => (iid, index + 1) }
      .toMap // +1 to make it starting from 1

    // TODO: output with key order?
    // TODO: also write extra item data (itypes, starttime, endtime, etc) into itemsindex
    itemsIndex.foreach {
      case (iid, iindex) =>
        itemsIndexWriter.write(s"${iindex}\t${iid}\n")
    }
    itemsIndexWriter.close()

    /* write u2i ratings */

    val u2iRatings = u2iDb.getAllByAppid(appid)
      .filter { u2i =>
        val validAction = isValidAction(u2i, arg.likeParam, arg.dislikeParam, arg.viewParam, arg.conversionParam)
        val validUser = usersIndex.contains(u2i.uid)
        val validItem = itemsIndex.contains(u2i.iid)
        (validAction && validUser && validItem)
      }.map { u2i =>
        val rating = convertToRating(u2i, arg.likeParam, arg.dislikeParam, arg.viewParam, arg.conversionParam)

        RatingData(
          uid = usersIndex(u2i.uid),
          iid = itemsIndex(u2i.iid),
          rating = rating,
          t = u2i.t.getMillis
        )
      }.toSeq

    if (!u2iRatings.isEmpty) {

      val ratingReduced = u2iRatings.groupBy(x => (x.iid, x.uid))
        .mapValues { v =>
          v.reduce { (a, b) =>
            resolveConflict(a, b, arg.conflictParam)
          }
        }.values
        .toSeq
        .sortBy { x: RatingData =>
          (x.iid, x.uid)
        }

      val fileName = if (arg.matrixMarket) "ratings.mm" else "ratings.csv"
      val ratingsWriter = new BufferedWriter(new FileWriter(new File(arg.outputDir + fileName))) // intermediate file

      if (arg.matrixMarket) {
        ratingsWriter.write("%%MatrixMarket matrix coordinate real general\n")
        ratingsWriter.write(s"${usersIndex.size} ${itemsIndex.size} ${ratingReduced.size}\n")
      }

      ratingReduced.foreach { r =>
        if (arg.matrixMarket) {
          ratingsWriter.write(s"${r.uid} ${r.iid} ${r.rating}\n")
        } else {
          ratingsWriter.write(s"${r.uid},${r.iid},${r.rating}\n")
        }
      }

      ratingsWriter.close()

      /*
      val fileName = if (arg.matrixMarket) "ratings.mm" else "ratings.csv"
      val ratingsWriter = new BufferedWriter(new FileWriter(new File(arg.outputDir + fileName))) // intermediate file

      if (arg.matrixMarket) {
        ratingsWriter.write("%%MatrixMarket matrix coordinate real general\n")
        ratingsWriter.write(s"${usersIndex.size} ${itemsIndex.size} ${u2iRatings.size}\n")
      }
      val last = u2iRatings
        .sortBy { x: RatingData =>
          (x.iid, x.uid) // NOTE: by iid first!
        }.reduceLeft { (prev: RatingData, cur: RatingData) =>

        if (cur.uid == prev.uid) {
          resolveConflict(cur, prev, arg.conflictParam)
        } else {
          if (arg.matrixMarket) {
            ratingsWriter.write(s"${prev.uid} ${prev.iid} ${prev.rating}\n")
          } else {
            ratingsWriter.write(s"${prev.uid},${prev.iid},${prev.rating}\n")
          }
          cur
        }
      }
      // write the last u2i
      if (arg.matrixMarket) {
        ratingsWriter.write(s"${last.uid} ${last.iid} ${last.rating}\n")
      } else {
        ratingsWriter.write(s"${last.uid},${last.iid},${last.rating}\n")
      }
      ratingsWriter.close()
      */
    }

  }

  def isValidAction(u2i: U2IAction, likeParam: Option[Int], dislikeParam: Option[Int],
    viewParam: Option[Int], conversionParam: Option[Int]): Boolean = {
    val keepThis: Boolean = u2i.action match {
      case ACTION_RATE => true
      case ACTION_LIKE => (likeParam != None)
      case ACTION_DISLIKE => (dislikeParam != None)
      case ACTION_VIEW => (viewParam != None)
      case ACTION_CONVERSION => (conversionParam != None)
      case _ => {
        assert(false, "Action type " + u2i.action + " in u2iActions appdata is not supported!")
        false // all other unsupported actions
      }
    }
    keepThis
  }

  def convertToRating(u2i: U2IAction, likeParam: Option[Int], dislikeParam: Option[Int],
    viewParam: Option[Int], conversionParam: Option[Int]): Int = {
    val rating: Int = u2i.action match {
      case ACTION_RATE => u2i.v.get.toInt
      case ACTION_LIKE => likeParam.getOrElse {
        assert(false, "Action type " + u2i.action + " should have been filtered out!")
        0
      }
      case ACTION_DISLIKE => dislikeParam.getOrElse {
        assert(false, "Action type " + u2i.action + " should have been filtered out!")
        0
      }
      case ACTION_VIEW => viewParam.getOrElse {
        assert(false, "Action type " + u2i.action + " should have been filtered out!")
        0
      }
      case ACTION_CONVERSION => conversionParam.getOrElse {
        assert(false, "Action type " + u2i.action + " should have been filtered out!")
        0
      }
    }
    rating
  }

  def resolveConflict(a: RatingData, b: RatingData, conflictParam: String) = {
    conflictParam match {
      case CONFLICT_LATEST => if (a.t > b.t) a else b
      case CONFLICT_HIGHEST => if (a.rating > b.rating) a else b
      case CONFLICT_LOWEST => if (a.rating < b.rating) a else b
    }
  }

  def cleanup(arg: JobArg) = {

  }

}