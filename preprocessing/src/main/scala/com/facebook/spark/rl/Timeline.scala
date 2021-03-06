// Copyright (c) Facebook, Inc. and its affiliates. All rights reserved.
package com.facebook.spark.rl

import scala.math.abs

import org.slf4j.LoggerFactory
import org.apache.spark.sql._
import org.apache.spark.sql.functions.coalesce
import org.apache.spark.sql.functions.udf

case class TimelineConfiguration(startDs: String,
                                 endDs: String,
                                 addTerminalStateRow: Boolean,
                                 actionDiscrete: Boolean,
                                 inputTableName: String,
                                 outputTableName: String,
                                 evalTableName: String,
                                 numOutputShards: Int,
                                 outlierEpisodeLengthPercentile: Option[Double] = None,
                                 percentileFunction: String = "percentile_approx")

/**
  * Given table of state, action, mdp_id, sequence_number, reward, possible_next_actions
  * return the table needed for reinforcement learning (MDP: Markov Decision Process)
  * mdp_id, state_features, action, reward, next_state_features, next_action,
  * sequence_number, sequence_number_ordinal, time_diff, possible_next_actions.
  * Shuffles the results.
  * Reference:
  * https://our.intern.facebook.com/intern/wiki/Reinforcement-learning/
  *
  * Args:
  * input_table: string, input table name
  *
  * output_table: string, output table name
  *
  * action_discrete: boolean, specify the action representation,
  * either 'discrete' or 'parametric'
  * True means discrete using  'String' as action,
  * False means parametric, using Map<BIGINT, DOUBLE>'
  *
  * add_terminal_state_row: boolean, if True assumes the final row
  * in each MDP corresponds to the terminal state and keeps it in output
  *
  * Columns of input table should contain:
  * mdp_id ( STRING ). A unique ID for the MDP chain that
  * this training example is a part of.
  *
  * state_features ( MAP<BIGINT,DOUBLE> ). The features of the current step
  * that are independent on the action.
  *
  * action ( STRING OR MAP<BIGINT,DOUBLE> ). The action taken at the current step.
  * A string if the action is discrete or
  * a set of features if the action is parametric.
  *
  * action_probability (DOUBLE). The probability that this action was taken.
  *
  * reward ( DOUBLE ). The reward at the current step.
  *
  * sequence_number ( BIGINT ).
  * A number representing the location of the state in the MDP.
  * There should be at most one row with the same mdp_id + sequence_number
  * (mdp_id + sequence_number makes a unique key).
  *
  * possible_actions ( ARRAY<STRING> OR ARRAY<MAP<BIGINT,DOUBLE>> ).
  * A list of actions that were possible at the current step.
  * This is optional but enables Q-Learning and improves model accuracy.
  *
  * This operator will generate output table with the following columns:
  * mdp_id ( STRING ). A unique ID for the MDP chain that
  * this training example is a part of.
  *
  * state_features ( MAP<BIGINT,DOUBLE> ). The features of the current step
  * that are independent on the action.
  *
  * action ( STRING OR MAP<BIGINT,DOUBLE> ). The action taken at the current step.
  * A string if the action is discrete or
  * a set of features if the action is parametric.
  *
  * action_probability (DOUBLE). The probability that this action was taken.
  *
  * reward ( DOUBLE ). The reward at the current step.
  *
  * next_state_features ( MAP<BIGINT,DOUBLE> ). The features of the subsequent
  * step that are action-independent.
  *
  * next_action (STRING OR MAP<BIGINT, DOUBLE> ). The action taken at the next step
  *
  * sequence_number ( BIGINT ).
  * A number representing the location of the state in the MDP before
  * the sequence_number was converted to an ordinal number.
  * There should be at most one row with the same mdp_id +
  * sequence_number (mdp_id + sequence_number makes a unique key).
  *
  * sequence_number_ordinal ( BIGINT ).
  * A number representing the location of the state in the MDP.
  * There should be at most one row with the same mdp_id +
  * sequence_number_ordinal (mdp_id + sequence_number_ordinal makes
  * a unique key).
  *
  * time_diff ( BIGINT ).
  * A number representing the number of states between the current
  * state and next state. If the input table is sub-sampled
  * states will be missing. This column allows us to know how many
  * states are missing which can be used to adjust the discount factor.
  *
  * possible_actions ( ARRAY<STRING> OR ARRAY<MAP<BIGINT,DOUBLE>> )
  * A list of actions that were possible at the current step.
  *
  * possible_next_actions ( ARRAY<STRING> OR ARRAY<MAP<BIGINT,DOUBLE>> )
  * A list of actions that were possible at the next step.
  *
  */
object Timeline {

  private val log = LoggerFactory.getLogger(this.getClass.getName)
  def run(sqlContext: SQLContext, config: TimelineConfiguration): Unit = {
    var filterTerminal = "HAVING next_state_features IS NOT NULL";
    if (config.addTerminalStateRow) {
      filterTerminal = "";
    }

    Timeline.validateOrDestroyTrainingTable(sqlContext,
                                            config.outputTableName,
                                            config.actionDiscrete)
    Timeline.createTrainingTable(sqlContext, config.outputTableName, config.actionDiscrete)

    val sourceTable = Timeline.mdpLengthThreshold(sqlContext, config) match {
      case Some(threshold) => s"""
      WITH a AS (${Timeline.getEpisodeLengthStatement(config)}),
      b AS (SELECT mdp_id FROM a WHERE mdp_length < ${threshold}),
      source_table AS (
          SELECT
              c.mdp_id,
              c.state_features,
              c.action,
              c.action_probability,
              c.reward,
              c.sequence_number,
              c.possible_actions,
              c.metrics
          FROM b JOIN ${config.inputTableName} c
          WHERE b.mdp_id = c.mdp_id
            AND c.ds BETWEEN '${config.startDs}' AND '${config.endDs}'
      )
      """.stripMargin
      case None            => s"""
      WITH source_table AS (
          SELECT
              mdp_id,
              state_features,
              action,
              action_probability,
              reward,
              sequence_number,
              possible_actions,
              metrics
          FROM ${config.inputTableName}
          WHERE ds BETWEEN '${config.startDs}' AND '${config.endDs}'
      )
      """.stripMargin
    }

    val sqlCommand = s"""
    ${sourceTable}
    SELECT
        mdp_id,
        state_features,
        action,
        action_probability,
        reward,
        LEAD(state_features) OVER (
            PARTITION BY
                mdp_id
            ORDER BY
                mdp_id,
                sequence_number
        ) AS next_state_features,
        LEAD(action) OVER (
            PARTITION BY
                mdp_id
            ORDER BY
                mdp_id,
                sequence_number
        ) AS next_action,
        sequence_number,
        ROW_NUMBER() OVER (
            PARTITION BY
                mdp_id
            ORDER BY
                mdp_id,
                sequence_number
        ) AS sequence_number_ordinal,
        COALESCE(LEAD(sequence_number) OVER (
            PARTITION BY
                mdp_id
            ORDER BY
                mdp_id,
                sequence_number
        ), sequence_number) - sequence_number AS time_diff,
        possible_actions,
        LEAD(possible_actions) OVER (
            PARTITION BY
                mdp_id
            ORDER BY
                mdp_id,
                sequence_number
        ) AS possible_next_actions,
        metrics
    FROM source_table
    ${filterTerminal}
    CLUSTER BY HASH(mdp_id, sequence_number)
    """.stripMargin
    log.info("Executing query: ")
    log.info(sqlCommand)
    var df = sqlContext.sql(sqlCommand)
    log.info("Done with query")

    // Handle nulls in output present when terminal states are present
    val nextAction = df("next_action")
    val possibleNextActions = df("possible_next_actions")

    val map_ = udf(() => Map.empty[Long, Double])
    val array_ = udf(() => Array.empty[String])
    val string_ = udf(() => "")
    val arrayOfMaps_ = udf(() => Array.empty[Map[Long, Double]])

    df = df.withColumn("next_state_features", coalesce(df("next_state_features"), map_()))
    if (config.actionDiscrete) {
      df = df
        .withColumn("next_action", coalesce(nextAction, string_()))
        .withColumn("possible_next_actions", coalesce(possibleNextActions, array_()))
    } else {
      df = df
        .withColumn("next_action", coalesce(nextAction, map_()))
        .withColumn("possible_next_actions", coalesce(possibleNextActions, arrayOfMaps_()))
    }

    val finalTableName = "finalTable"
    df.createOrReplaceTempView(finalTableName)

    val insertCommand = s"""
      INSERT OVERWRITE TABLE ${config.outputTableName} PARTITION(ds='${config.endDs}')
      SELECT * FROM ${finalTableName}
    """.stripMargin
    sqlContext.sql(insertCommand)
  }

  def getEpisodeLengthStatement(config: TimelineConfiguration): String = s"""
      SELECT mdp_id, COUNT(1) mdp_length
      FROM ${config.inputTableName}
      WHERE ds BETWEEN '${config.startDs}' AND '${config.endDs}'
      GROUP BY 1
  """

  def mdpLengthThreshold(sqlContext: SQLContext, config: TimelineConfiguration): Option[Int] =
    config.outlierEpisodeLengthPercentile match {
      case Some(percentile) => {
        val episodeLengthStatement = Timeline.getEpisodeLengthStatement(config)
        val df = sqlContext.sql(s"""
            WITH a AS (${episodeLengthStatement}),
            b AS (
                SELECT CAST(${config.percentileFunction}(mdp_length, ${percentile}) AS INT) pct FROM a
            ),
            c AS (
                SELECT
                    count(1) as mdp_count,
                    count(IF(a.mdp_length >= b.pct, 1, NULL)) as outlier_count
                FROM a, b
            )
            SELECT b.pct, c.mdp_count, c.outlier_count
            FROM b, c
        """)
        val res = df.first
        val pct_val = res.getAs[Int]("pct")
        val mdp_count = res.getAs[Long]("mdp_count")
        val outlier_count = res.getAs[Long]("outlier_count")
        log.info(s"Threshold: ${pct_val}; mdp count: ${mdp_count}; outlier_count: ${outlier_count}")
        val outlier_percent = outlier_count.toDouble / mdp_count
        val expected_outlier_percent = 1.0 - percentile
        if (abs(outlier_percent - expected_outlier_percent) / expected_outlier_percent > 0.1) {
          log.warn(
            s"Outlier percent mismatch; expected: ${expected_outlier_percent}; got ${outlier_percent}")
          None
        } else
          Some(pct_val)
      }
      case None => None
    }

  def validateOrDestroyTrainingTable(sqlContext: SQLContext,
                                     tableName: String,
                                     actionDiscrete: Boolean): Unit =
    try {
      val checkOutputTableCommand = s"""
      DESCRIBE ${tableName}
      """
      val df = sqlContext.sql(checkOutputTableCommand);
      // Validate the schema and destroy the output table if it doesn't match
      var validTable = Helper.outputTableIsValid(sqlContext, tableName, actionDiscrete)
      if (!validTable) {
        val dropTableCommand = s"""
        DROP TABLE ${tableName}
        """
        sqlContext.sql(dropTableCommand);
      }
    } catch {
      case e: org.apache.spark.sql.catalyst.analysis.NoSuchTableException => {}
      case e: Throwable                                                   => log.error(e.toString())
    }
  def createTrainingTable(sqlContext: SQLContext,
                          tableName: String,
                          actionDiscrete: Boolean): Unit = {
    var actionType = "STRING";
    var possibleActionType = "ARRAY<STRING>";
    if (!actionDiscrete) {
      actionType = "MAP<BIGINT, DOUBLE>"
      possibleActionType = "ARRAY<MAP<BIGINT,DOUBLE>>"
    }

    val sqlCommand = s"""
CREATE TABLE IF NOT EXISTS ${tableName} (
    mdp_id STRING,
    state_features MAP < BIGINT,
    DOUBLE >,
    action ${actionType},
    action_probability DOUBLE,
    reward DOUBLE,
    next_state_features MAP < BIGINT,
    DOUBLE >,
    next_action ${actionType},
    sequence_number BIGINT,
    sequence_number_ordinal BIGINT,
    time_diff BIGINT,
    possible_actions ${possibleActionType},
    possible_next_actions ${possibleActionType},
    metrics MAP< STRING, DOUBLE>
) PARTITIONED BY (ds STRING) TBLPROPERTIES ('RETENTION'='30')
""".stripMargin
    sqlContext.sql(sqlCommand);
  }
}
