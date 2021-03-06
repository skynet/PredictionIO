/** Copyright 2014 TappingStone, Inc.
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

package io.prediction.controller

import io.prediction.core.BaseMetrics

import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD

import scala.reflect._
import scala.reflect.runtime.universe._

/** Base class of metrics.
  *
  * Metrics compare predictions with actual known values and produce numerical
  * comparisons.
  *
  * @tparam MP Metrics parameters class.
  * @tparam PD Prepared data class.
  * @tparam Q Input query class.
  * @tparam P Output prediction class.
  * @tparam A Actual value class.
  * @tparam MU Metrics unit class.
  * @tparam MR Metrics result class.
  * @tparam MMR Multiple metrics results class.
  * @group Metrics
  */
abstract class Metrics[
    MP <: Params : ClassTag, DP, Q, P, A, MU, MR, MMR <: AnyRef]
  extends BaseMetrics[MP, DP, Q, P, A, MU, MR, MMR] {

  def computeUnitBase(input: (Q, P, A)): MU = {
    computeUnit(input._1, input._2, input._3)
  }

  /** Implement this method to calculate a unit of metrics, comparing a pair of
    * predicted and actual values.
    *
    * @param query Input query that produced the prediction.
    * @param prediction The predicted value.
    * @param actual The actual value.
    */
  def computeUnit(query: Q, prediction: P, actual: A): MU

  def computeSetBase(dataParams: DP, metricUnits: Seq[MU]): MR = {
    computeSet(dataParams, metricUnits)
  }

  /** Implement this method to calculate metrics results of an evaluation.
    *
    * @param dataParams Data parameters that were used to generate data for this
    *                   evaluation.
    * @param metricUnits A list of metric units from [[computeUnit]].
    */
  def computeSet(dataParams: DP, metricUnits: Seq[MU]): MR

  def computeMultipleSetsBase(input: Seq[(DP, MR)]): MMR = {
    computeMultipleSets(input)
  }

  /** Implement this method to aggregate all metrics results generated by
    * each evaluation's [[computeSet]] to produce the final result.
    *
    * @param input A list of data parameters and metric unit pairs to aggregate.
    */
  def computeMultipleSets(input: Seq[(DP, MR)]): MMR
}

/** Trait for nice metrics results
  *
  * Metrics result can be rendered nicely by implementing toHTML and toJSON
  * methods. These results are rendered through dashboard.
  * @group Metrics
  */
trait NiceRendering {
  /** HTML portion of the rendered metrics results. */
  def toHTML(): String

  /** JSON portion of the rendered metrics results. */
  def toJSON(): String
}

/** An implementation of mean square error metrics. `DP` is `AnyRef`. This
  * support any kind of data parameters.
  *
  * @group Metrics
  */
class MeanSquareError extends Metrics[EmptyParams, AnyRef,
    AnyRef, Double, Double, (Double, Double), String, String] {
  def computeUnit(q: AnyRef, p: Double, a: Double): (Double, Double) = (p, a)

  def computeSet(ep: AnyRef, data: Seq[(Double, Double)]): String = {
    val units = data.map(e => math.pow(e._1 - e._2, 2))
    val mse = units.sum / units.length
    f"Set: $ep Size: ${data.length} MSE: ${mse}%8.6f"
  }

  def computeMultipleSets(input: Seq[(AnyRef, String)]): String = {
    input.map(_._2).mkString("\n")
  }
}
