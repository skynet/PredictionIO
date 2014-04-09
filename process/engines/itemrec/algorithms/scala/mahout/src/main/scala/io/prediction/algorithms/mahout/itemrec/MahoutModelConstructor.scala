package io.prediction.algorithms.mahout.itemrec

import grizzled.slf4j.Logger
import com.twitter.scalding.Args
import scala.io.Source

import io.prediction.commons.Config
import io.prediction.commons.modeldata.{ ItemRecScore }

/**
 * Description:
 * Model constuctor for non-distributed (single machine) Mahout ItemRec algo
 *
 * Input files:
 * - predicted.tsv (uindex prediction-string) prediction output generated by MahoutJob
 * - ratings.csv (uindex iindex rating)
 * - itemsIndex.tsv (iindex iid itypes starttime endtime)
 * - usersIndex.tsv (uindex uid)
 *
 * Required args:
 * --appid: <int>
 * --algoid: <int>
 * --modelSet: <boolean> (true/false). flag to indicate which set
 *
 * --unseenOnly: <boolean> (true/false). only recommend unseen items if this is true.
 * --numRecommendations: <int>. number of recommendations to be generated
 *
 * Optionsl args:
 * --evalid: <int>. Offline Evaluation if evalid is specified
 * --debug: <String>. "test" - for testing purpose
 *
 * --booleanData: <boolean>. Mahout item rec algo flag for implicit action data
 * --implicitFeedback: <boolean>. Mahout item rec algo flag for implicit action data
 *
 */
object MahoutModelConstructor {
  /* global */
  val logger = Logger(MahoutModelConstructor.getClass)
  val commonsConfig = new Config

  // argument of this job
  case class JobArg(
    val inputDir: String,
    val appid: Int,
    val algoid: Int,
    val evalid: Option[Int],
    val modelSet: Boolean,
    val unseenOnly: Boolean,
    val numRecommendations: Option[Int],
    val booleanData: Boolean,
    val implicitFeedback: Boolean)

  def main(cmdArgs: Array[String]) {
    logger.info("Running model constructor for Mahout ...")
    logger.info(cmdArgs.mkString(","))

    /* get arg */
    val args = Args(cmdArgs)

    val arg = JobArg(
      inputDir = args("inputDir"),
      appid = args("appid").toInt,
      algoid = args("algoid").toInt,
      evalid = args.optional("evalid") map (x => x.toInt),
      modelSet = args("modelSet").toBoolean,
      unseenOnly = args.optional("unseenOnly").map(_.toBoolean).getOrElse(false),
      numRecommendations = args.optional("numRecommendations").map(x => x.toInt),
      booleanData = args.optional("booleanData").map(x => x.toBoolean).getOrElse(false),
      implicitFeedback = args.optional("implicitFeedback").map(x => x.toBoolean).getOrElse(false)
    )

    /* run job */
    modelCon(arg)
    cleanUp(arg)
  }

  def modelCon(arg: JobArg) = {
    // implicit preference flag.
    val IMPLICIT_PREFERENCE = arg.booleanData || arg.implicitFeedback

    // NOTE: if OFFLINE_EVAL, write to training modeldata and use evalid as appid
    val OFFLINE_EVAL = (arg.evalid != None)

    val modeldataDb = if (!OFFLINE_EVAL)
      commonsConfig.getModeldataItemRecScores
    else
      commonsConfig.getModeldataTrainingItemRecScores

    val appid = if (OFFLINE_EVAL) arg.evalid.get else arg.appid

    // user index file
    // uindex -> uid
    val usersMap: Map[Int, String] = Source.fromFile(s"${arg.inputDir}usersIndex.tsv").getLines()
      .map[(Int, String)] { line =>
        val (uindex, uid) = try {
          val data = line.split("\t")
          (data(0).toInt, data(1))
        } catch {
          case e: Exception => {
            throw new RuntimeException(s"Cannot get user index and uid in line: ${line}. ${e}")
          }
        }
        (uindex, uid)
      }.toMap

    val itemsMap = MahoutCommons.itemsMap(s"${arg.inputDir}itemsIndex.tsv")

    // ratings file (for unseen filtering)
    val seenMap: Map[(Int, Int), Double] = if (arg.unseenOnly) {
      Source.fromFile(s"${arg.inputDir}ratings.csv")
        .getLines()
        .map { line =>
          val (u, i, r) = try {
            val fields = line.split(",")
            // u, i, rating
            (fields(0).toInt, fields(1).toInt, fields(2).toDouble)
          } catch {
            case e: Exception => throw new RuntimeException(s"Cannot get user and item index from this line: ${line}. ${e}")
          }
          ((u, i) -> r)
        }.toMap
    } else {
      Map()
    }

    /* TODO: handling merging seen rating
    // uindx -> Seq[(iindex, rating)]
    val ratingsMap: Map[Int, Seq[(Int, Double)]] = seenMap.groupBy { case ((u, i), s) => u }
      .mapValues { v =>
        // v is Map[()]
        v.toSeq.map { case ((u, i), s) => (i, s) }
      }
    */

    // prediction
    Source.fromFile(s"${arg.inputDir}predicted.tsv")
      .getLines()
      .foreach { line =>
        val fields = line.split("\t")

        val (uindex, predictedData) = try {
          (fields(0).toInt, fields(1))
        } catch {
          case e: Exception => throw new RuntimeException(s"Cannot extract uindex and prediction output from this line: ${line}. ${e}")
        }

        val predicted: Seq[(Int, Double)] = parsePredictedData(predictedData)
          .map { case (iindex, rating) => (iindex.toInt, rating) }

        // TODO: handling merging seen rating
        val combined = predicted
        // if unseenOnly (or implicit preference), no merge with known rating
        /*if (arg.unseenOnly || IMPLICIT_PREFERENCE) predicted
        else (predicted ++ ratingsMap.getOrElse(uindex, Seq()))*/

        val topScoresAll = combined
          .filter {
            case (iindex, rating) =>
              unseenItemFilter(arg.unseenOnly, uindex, iindex, seenMap) &&
                validItemFilter(true, iindex, itemsMap)
          }.sortBy(_._2)(Ordering[Double].reverse)
        val topScores = arg.numRecommendations.map(x => topScoresAll.take(x)).getOrElse(topScoresAll)

        logger.debug(s"$topScores")

        val uid = try {
          usersMap(uindex)
        } catch {
          case e: Exception => throw new RuntimeException(s"Cannot get uid for this uindex: ${line}. ${e}")
        }
        modeldataDb.insert(ItemRecScore(
          uid = usersMap(uindex),
          iids = topScores.map(x => itemsMap(x._1).iid),
          scores = topScores.map(_._2),
          itypes = topScores.map(x => itemsMap(x._1).itypes),
          appid = appid,
          algoid = arg.algoid,
          modelset = arg.modelSet))

      }
  }

  def cleanUp(arg: JobArg) = {

  }

  /* TODO refactor this
  Mahout ItemRec output format
  [24:3.2] => (24, 3.2)
  [8:2.5,0:2.5]  => (8, 2.5), (0, 2.5)
  [0:2.0]
  [16:3.0]
  */
  def parsePredictedData(data: String): List[(String, Double)] = {
    val dataLen = data.length
    data.take(dataLen - 1).tail.split(",").toList.map { ratingData =>
      val ratingDataArray = ratingData.split(":")
      val item = ratingDataArray(0)
      val rating: Double = try {
        ratingDataArray(1).toDouble
      } catch {
        case e: Exception =>
          {
            assert(false, s"Cannot convert rating value of item ${item} to double: " + ratingDataArray + ". Exception: " + e)
          }
          0.0
      }
      (item, rating)
    }
  }

  def unseenItemFilter(enable: Boolean, uindex: Int, iindex: Int, seenMap: Map[(Int, Int), Any]): Boolean = {
    if (enable) (!seenMap.contains((uindex, iindex))) else true
  }

  def validItemFilter(enable: Boolean, iindex: Int, validMap: Map[Long, Any]): Boolean = {
    if (enable) validMap.contains(iindex) else true
  }

}
