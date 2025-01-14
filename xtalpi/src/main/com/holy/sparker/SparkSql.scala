package com.holy.sparker

import java.sql.Timestamp

import org.apache.spark.TaskContext
import com.holy.extra.caseClass.Record
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{DataTypes, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Dataset, Row, SaveMode, SparkSession}
import org.junit.Test

import scala.collection.mutable
import scala.util.matching.Regex


class SparkSql {
    val tempPath: String = HadoopUtils.workHome + "/tmp"
    val peopleJsonPath: String = HadoopUtils.sparkHome +  "/examples/src/main/resources/people.json"
    val usersParquetPath: String = HadoopUtils.sparkHome + "/examples/src/main/resources/users.parquet"
    val spark: SparkSession = SparkSession.builder().appName("SparkDataSource")
        // NOTE Not allowing to set spark.sql.warehouse.dir or hive.metastore.warehouse.dir
        // NOTE in SparkSession's options, it should be set statically for cross-session usages
        .config("spark.sql.warehouse.dir", tempPath + "/warehouse")
        .enableHiveSupport().master("local[*]").getOrCreate()

    HadoopUtils.hadoopConfig(spark.sparkContext.hadoopConfiguration)
    // NOTE Setting hive.metastore.warehouse.dir ('null') to the value of spark.sql.warehouse.dir
    spark.sparkContext.hadoopConfiguration.addResource("hive-site.xml")
    spark.sparkContext.setLogLevel("WARN")

    // Encoders for most common types are automatically provided by importing spark.implicits._
    import spark.implicits._

    def parquetTest(): Unit ={
        // NOTE parquet
        /**
         * Parquet is a columnar format that is supported by many other data processing systems.
         * Spark SQL provides support for both reading and writing Parquet files
         * that automatically preserves the schema of the original data. When reading Parquet files,
         * all columns are automatically converted to be nullable for compatibility reasons.
         */
        val peopleDF = spark.read.json(peopleJsonPath)
        val peopleParquetPath = tempPath + "/people/people.parquet"
        // DataFrames can be saved as Parquet files, maintaining the schema information
        peopleDF.write.mode(SaveMode.Overwrite).parquet(peopleParquetPath)

        // Parquet files are self-describing so the schema is preserved
        val parquetFileDF = spark.read.parquet(peopleParquetPath)
        parquetFileDF.createOrReplaceTempView("parquetFile")
        val namesDF = spark.sql("SELECT name FROM parquetFile WHERE age BETWEEN 13 AND 19")
        namesDF.map(attributes => "Name: " + attributes(0)).show()

        val usersDF = spark.read.load(usersParquetPath)
        println(usersDF.columns.mkString("Array(", ", ", ")"))
        usersDF.select("name", "favorite_color").write.mode(
            SaveMode.Ignore).save(tempPath + "/user/parquet.namesAndFavColors")

        // NOTE Partition Discovery
        /**
         * Table partitioning is a common optimization approach used in systems like Hive.
         * In a partitioned table, data are usually stored in different directories,
         * with partitioning column values encoded in the path of each partition directory.
         * All built-in file sources are able to discover and infer partitioning information automatically
         * (including Text/CSV/JSON/ORC/Parquet)
         *
         * By passing path/to/table to either SparkSession.read.parquet or SparkSession.read.load,
         * Spark SQL will automatically extract the partitioning information from the paths
         */

        //the automatic type inference can be configured by
        spark.sql("set spark.sql.sources.partitionColumnTypeInference.enabled=true")

        /**
         *  Starting from Spark 1.6.0, partition discovery only finds partitions under the given paths by default.
         *  if users pass path/to/table/gender=male to either SparkSession.read.parquet or SparkSession.read.load,
         *  "gender" will not be considered as a partitioning column.
         *
         *  If users need to specify the base path that partition discovery should start with,
         *  they can set "basePath" in the data source options.
         *  For example, when path/to/table/gender=male is the path of the data and
         *  users set basePath to path/to/table/, gender will be a partitioning column.
         */

        // NOTE Schema Merging
        /**
         * Like Protocol Buffer, Avro, and Thrift, Parquet also supports schema evolution.
         * Users can start with a simple schema, and gradually add more columns to the schema as needed.
         * In this way, users may end up with multiple Parquet files with different but mutually compatible schemas.
         * The Parquet data source is now able to automatically detect this case and merge schemas of all these files.
         *
         * Since schema merging is a relatively expensive operation, and is not a necessity in most cases.
         * we turned it off by default starting from 1.5.0. You may enable it by
         *
         * 1. setting data source option mergeSchema to true when reading Parquet files
         * 2. setting the global SQL option spark.sql.parquet.mergeSchema to true
         */

        val squaresDF = spark.sparkContext
            .makeRDD(1 to 5).map(i => (i, i * i)).toDF("value", "square")
        squaresDF.write.mode(SaveMode.Ignore).parquet(tempPath + "/test_table/key=1")

        // NOTE  注意理解列式存储 ....
        // adding a new column and dropping an existing column
        val cubesDF = spark.sparkContext
            .makeRDD(6 to 10).map(i => (i, i * i * i)).toDF("value", "cube")
        cubesDF.write.mode(SaveMode.Ignore) parquet(tempPath + "/test_table/key=2")

        val mergedDF = spark.read.option("mergeSchema", "true").parquet(tempPath + "/test_table")
        mergedDF.printSchema()  // TODO 笛卡尔积 全连接 ??
        mergedDF.show()

        // NOTE Hive metastore Parquet table conversion
        /**
         * When reading from Hive metastore Parquet tables and
         * writing to non-partitioned Hive metastore Parquet tables,
         * Spark SQL will try to use its own Parquet support instead of Hive SerDe for better performance.
         *
         * This behavior is controlled by the "spark.sql.hive.convertMetastoreParquet" configuration,
         * and is turned on by default.
         */

        // NOTE Hive/Parquet Schema Reconciliation
        /**
         * There are two key differences between Hive and Parquet from the perspective of table schema processing.
         * 1- Hive is case insensitive, while Parquet is not
         * 2- Hive considers all columns nullable, while nullability in Parquet is significant
         *
         * Due to this reason, we must reconcile Hive metastore schema with Parquet schema
         * when converting a Hive metastore Parquet table to a Spark SQL Parquet table.
         * The reconciliation rules are:
         * 1 - Fields that have the same name in both schema must have the same data type regardless of nullability.
         *     The reconciled field should have the data type of the Parquet side, so that nullability is respected.
         * 2 - The reconciled schema contains exactly those fields defined in Hive metastore schema.
         *     Any fields that only appear in the
         *          1 - Parquet schema are dropped in the reconciled schema.
         *          2 - Hive metastore schema are added as nullable field in the reconciled schema.
         */

        // NOTE Metadata Refreshing
        /**
         * Spark SQL caches Parquet metadata for better performance.
         * When Hive metastore Parquet table conversion is enabled,
         * metadata of those converted tables are also cached.
         * If these tables are updated by Hive or other external tools,
         * you need to refresh them manually to ensure consistent metadata.
         */
        spark.catalog.refreshTable("parquetFile")
        spark.sql("REFRESH TABLE parquetFile;")

        // NOTE Configuration
        /**
         * Configuration of Parquet can be done using the setConf method on SparkSession or
         * by running SET key=value commands using SQL.
         *
         * write option : parquet.enable.dictionary
         */
    }

    def orcTest(): Unit ={
        // NOTE ORC
        /**
         * Since Spark 2.3, Spark supports a vectorized ORC reader with a new ORC file format for ORC files.
         * To do that, the following configurations are newly added.
         * The vectorized reader is used for the native ORC tables (e.g., the ones created using the clause USING ORC)
         * when spark.sql.orc.impl is set to native and spark.sql.orc.enableVectorizedReader is set to true.
         * For the Hive ORC serde tables (e.g., the ones created using the clause USING HIVE OPTIONS (fileFormat 'ORC')),
         * the vectorized reader is used when spark.sql.hive.convertMetastoreOrc is also set to true.
         */
        // 是否启用dictionary编码；默认大小与page.size相同，为1M。dictionary创建时会占用较多的内存。
        val usersDF = spark.read.load(usersParquetPath)
        usersDF.write.format("orc")
            .option("orc.bloom.filter.columns", "favorite_color")
            .option("orc.dictionary.key.threshold", "1.0")
            .option("orc.column.encoding.direct", "name")
            .save(tempPath +  "/orc/users_with_options.orc")
    }

    def jsonTest(){
        // NOTE json
        /**
         * Note that the file that is offered as a json file is not a typical JSON file.
         * Each line must contain a separate, self-contained valid JSON object
         *
         * For a regular multi-line JSON file, set the multiLine option to true.
         */
        // val peopleDF = spark.read.format("json").load(peopleJsonPath)
        val peopleDF = spark.read.json(peopleJsonPath)
        peopleDF.printSchema()
        peopleDF.createOrReplaceTempView("people")
        val teenagerNamesDF = spark.sql("SELECT name FROM people WHERE age BETWEEN 13 AND 19")
        teenagerNamesDF.show()

        // NOTE partitionBy and bucketBY
        peopleDF.select("name", "age").write.mode(SaveMode.Ignore)
            .format("parquet").save(tempPath + "/people/parquet.json")
        peopleDF.write.bucketBy(42, "name")
            .sortBy("age").saveAsTable("people_bucketed")

        /**
         * Alternatively, a DataFrame can be created for a JSON dataset
         * represented by a Dataset[String] storing one JSON object per string
         */
        val otherPeopleDataset = spark.createDataset(
            """{"name":"Yin","address":{"city":"Columbus","state":"Ohio"}}""" :: Nil)
        val otherPeople = spark.read.json(otherPeopleDataset)
        otherPeople.show()
    }

    @Test
    def csvTest(): Unit = {
        // NOTE scala 在数据清洗，字符串处理层面远没有 python 方便
        // val peopleCsvPath: String = HadoopUtils.sparkHome + "/examples/src/main/resources/people.csv"
        // 配置了 hadoop 默认的schema 是 hdfs:// 读取本地文件添加 schema file:///
        val columns = Array("id", "name", "category", "project", "cluster", "creator", "datasets", "result",
                            "status", "start_time", "end_time", "image", "git_url", "git_branch", "git_commit",
                            "command", "cpu", "gpu", "spot", "memory", "gpu_model", "relation_report")
        val structFields = columns.map(StructField(_, DataTypes.StringType, nullable = true))
        val fieldSchema = StructType(structFields)
        val infoCsvDF = spark.read.format("csv")
            .option("sep", ",").option("inferSchema", "false").option("header", "false").schema(fieldSchema)
            .load("hdfs://hadoop01/home/holyzing/nohead_batchjob_info.csv")

        val boolReg: Regex = "true|false".r()

        // import spark.implicits._
        // NOTE 由于类型不一致，RDD 类型只能存储为 Any ？？？ DataSet[Row] 不存在隐式转换，只能转换为 RDD
        //      这额外增加了类型转换步骤，如何避免

        def parseIntFromStr = (x: Row) => {
            val tm = x.get(0).asInstanceOf[String]
            if (tm.matches("\\d+")) {
                tm.toInt
            } else {
                null
            }
        }

        val startTime: RDD[Any] = infoCsvDF.select("start_time").rdd.map(parseIntFromStr)
        val endTime: RDD[Any] = infoCsvDF.select("end_time").rdd.map(parseIntFromStr)

        val intReg: Regex = "\\d+".r()
        def extractIntFromStr = (x: Row)=> intReg.findFirstIn(x.get(0).asInstanceOf[String]).getOrElse("0").toInt

        val cpu: Dataset[Int] = infoCsvDF.select("cpu").map(extractIntFromStr)
        val gpu: Dataset[Int] = infoCsvDF.select("gpu").map(extractIntFromStr)
        val memory: Dataset[Int] = infoCsvDF.select("memory").map(extractIntFromStr)
        val spot: Dataset[Boolean] = infoCsvDF.select("spot").map(x=>{
            boolReg.findFirstIn(x.get(0).asInstanceOf[String]).getOrElse("false").toBoolean})
        val gpuModel: Dataset[String] = infoCsvDF.select("gpu_model").map(x=>{
            val str = x.get(0).asInstanceOf[String].split(": ")(1)
            str.substring(1, str.length-3)}
        )
        println(startTime.first(), endTime.first(), cpu.first(), gpu.first(),
                memory.first(), spot.first(), gpuModel.first()) // collect 触发action

        // TODO 如何以每一列的形式直接存入 HBase 而不是组成行，存入 Hbase ??? 分布式的显然不适合以但内存的思维方式操作

        // def typeConverter(row: Row): Row ={
        //     // NOTE： _*将 一个 seq 拆分为 参数
        //     val mMap: mutable.Map[String, Any] = scala.collection.mutable.Map(
        //         row.getValuesMap[String](columns).toSeq: _*)
        //     // map values 不一定是业务需要的数据
        //     val startTime = mMap.getOrElse("start_time", null)
        //     if (startTime != null){
        //     }
        //     row
        // }

        // infoCsvDF.printSchema()
        // println(infoCsvDF.columns.mkString(","))

        // val row = infoCsvDF.tail(1)
        // println(row.mkString("   "))
        // infoCsvDF.show()
    }

    def hiveTest(): Unit ={
        // NOTE Saving to Persistent Tables
        /**
         * DataFrames can also be saved as persistent tables into Hive metastore using the saveAsTable command.
         * Notice that an existing Hive deployment is not necessary to use this feature.
         * Spark will create a default local Hive metastore (using Derby) for you.
         *
         * Unlike the createOrReplaceTempView command, saveAsTable will materialize the contents of the DataFrame
         * and create a pointer to the data in the Hive metastore.
         *
         * Persistent tables will still exist even after your Spark program has restarted,
         * as long as you maintain your connection to the same metastore.
         *
         * A DataFrame for a persistent table can be created by calling
         * the table method on a SparkSession with the name of the table.
         *
         * For file-based data source, e.g. text, parquet, json, etc.
         * you can specify a custom table path via the path option,
         * e.g. df.write.option("path", "/some/path").saveAsTable("t").
         * When the table is dropped, the custom table path will not be removed and the table data is still there.
         *
         * If no custom table path is specified,
         * Spark will write data to a default table path under the warehouse directory.
         * When the table is dropped, the default table path will be removed too.
         *
         * Starting from Spark 2.1, persistent datasource tables have per-partition metadata stored in the Hive metastore.
         * This brings several benefits:
         *  1 - Since the metastore can return only necessary partitions for a query,
         *      discovering all the partitions on the first query to the table is no longer needed.
         *  2 - Hive DDLs such as ALTER TABLE PARTITION ... SET LOCATION
         *      are now available for tables created with the Datasource API.
         *
         * Note that partition information is not gathered by default
         * when creating external datasource tables (those with a path option).
         * To sync the partition information in the metastore, you can invoke MSCK REPAIR TABLE.
         */

        /**
         * When working with Hive, one must instantiate SparkSession with Hive support, including connectivity to a
         * persistent Hive metastore, support for Hive serdes, and Hive user-defined functions.
         * Users who do not have an existing Hive deployment can still enable Hive support.
         * When not configured by the hive-site.xml, the context automatically creates metastore_db
         * in the current directory and creates a directory configured by spark.sql.warehouse.dir, which defaults
         * to the directory spark-warehouse in the current directory that the Spark application is started.
         * Note that the hive.metastore.warehouse.dir property in hive-site.xml is deprecated since Spark 2.0.0.
         * Instead, use spark.sql.warehouse.dir to specify the default location of database in warehouse.
         * You may need to grant write privilege to the user who starts the Spark application.
         *
         * NOTE 当没有通过 hive-site.xml 连接到外部的hive，saprk中的配置是无效且不被允许的， 而hive中的配置是有效的，
         * NOTE 相反的，当通过 hive-site.xml 连接到外部的 hive，则hive中的 配置失效，而spark中的配置生效。
         *
         * NOTE spark metastore_db(derby)
         * NOTE spark.driver.extraJavaOptions -Dderby.system.home=/tmp/derby
         * LATER 配置不生效 ?????????
         */

        // NOTE spark.sql("MSCK REPAIR TABLE src")
        // NOTE 修复已经存在的表的信息(分区信息等),对新增分区可以更新,但是对删除分区则无法更新

        // THINK 为什么 在 Linux 下 if not exists 不生效 ??? 因为 metastore_db 中没有src 这个表信息
        // THINK 但是在 warehouse 中是有这个目录的。
        spark.sql("CREATE TABLE IF NOT EXISTS src (key INT, value STRING) USING hive")
        // NOTE load 会拷贝源文件到warehouse，并存储其 schema 信息, 如果同名文件存在，则按顺讯编号存储
        val loadDataSql = "LOAD DATA LOCAL INPATH '%s/examples/src/main/resources/kv1.txt' INTO TABLE src"
            .format(HadoopUtils.sparkHome)
        println(loadDataSql)
        spark.sql(s"LOAD DATA LOCAL INPATH '${HadoopUtils.sparkHome}" +
            "/examples/src/main/resources/kv1.txt' INTO TABLE src")
        // Queries are expressed in HiveQL
        spark.sql("SELECT * FROM src").show()
        spark.sql("SELECT COUNT(*) FROM src").show()

        // The results of SQL queries are themselves DataFrames and support all normal functions.
        val sqlDF = spark.sql("SELECT key, value FROM src WHERE key < 10 ORDER BY key")
        // The items in DataFrames are of type Row, which allows you to access each column by ordinal.
        val stringsDS = sqlDF.map{case Row(key: Int, value: String) => s"Key: $key, Value: $value"}
        stringsDS.show()

        // You can also use DataFrames to create temporary views within a SparkSession.
        val recordsDF = spark.createDataFrame((1 to 100).map(i => Record(i, s"val_$i")))
        recordsDF.createOrReplaceTempView("records")
        // Queries can then join DataFrame data with data stored in Hive.
        spark.sql("SELECT * FROM records r JOIN src s ON r.key = s.key").show()

        // Create a Hive managed Parquet table, with HQL syntax instead of the Spark SQL native syntax
        // `USING hive`
        spark.sql("CREATE TABLE hive_records(key int, value string) STORED AS PARQUET")
        // Save DataFrame to the Hive managed table
        val df = spark.table("src")
        df.write.mode(SaveMode.Overwrite).saveAsTable("hive_records")
        // After insertion, the Hive managed table has data now
        spark.sql("SELECT * FROM hive_records").show()

        // Prepare a Parquet data directory
        val dataDir = tempPath +  "/parquet_data"
        spark.range(10).write.parquet(dataDir)
        // Create a Hive external Parquet table
        spark.sql(s"CREATE EXTERNAL TABLE hive_bigints(id bigint) STORED AS PARQUET LOCATION '$dataDir'")
        // The Hive external table should already have data
        spark.sql("SELECT * FROM hive_bigints").show()
        // ... Order may vary, as spark processes the partitions in parallel.

        // Turn on flag for Hive Dynamic Partitioning
        spark.sqlContext.setConf("hive.exec.dynamic.partition", "true")
        spark.sqlContext.setConf("hive.exec.dynamic.partition.mode", "nonstrict")
        // Create a Hive partitioned table using DataFrame API
        spark.table("src").write.partitionBy("key")
            .format("hive").saveAsTable("hive_part_tbl")
        // Partitioned column `key` will be moved to the end of the schema.
        spark.sql("SELECT * FROM hive_part_tbl").show()

        // spark.sql("select count(*) from hive_part_tbl").show()
        // spark.sql("select max(key), min(key) from hive_part_tbl").show()
        // spark.table("show partitions hive_part_tbl")

        val hptDf = spark.table("hive_part_tbl")
        println(hptDf.rdd.getNumPartitions)
        // 注意在 集群内 foreach 进行打印是不正确的,需要 collect 到 driver 之后去打印

        var count = 0
        var sum_ = 0
        for (part <- hptDf.rdd.glom().collect()){
            if (part.length > 1){
                count += 1
                sum_ += part.length
            }
        }
        // 500 条数据, 按 key 分区存储 有 309 个分区， 最小的key 是 0 最大的key是 498，
        // 136 个元素个数大于1的分区, 元素总个数是 327
        // 309 - 136 = 173, 173 + 327 = 500

        println(count, sum_)
        // hptDf.foreachPartition( (iter: Iterator[Row]) => {
        //    if (iter.length > 1){
        //        println(iter.toString(), iter.length)
        //    }
        // })
        println(TaskContext.getPartitionId(), TaskContext.get().partitionId())

        // NOTE Specifying storage format for Hive tables

        // NOTE Interacting with Different Versions of Hive Metastore
    }

    def jdbcToOtherDatabases(): Unit = {

    }
    // -------------------------------------------------------------------------------------------------------------

    def apacheAvroDataTest(): Unit = {

    }

    def partion(): Unit ={
        /**
         * DataFrame 分区 默认按字段顺序分区 (Cassandra 列式存储), 比如 先 gender 后 country
         * 读取的时候会抽取分区信息,返回 DataFrame 的表结构
         * data 按 column 分区后,会为每一个分区持久化元信息,这为更多 类 SQL 的 DDL 操作 成为现实
         */
        val usersDF = spark.sql("SELECT * FROM parquet.`"+ usersParquetPath +"`")

        usersDF.write.mode("overwrite").partitionBy("favorite_color")
            .format("parquet").save(tempPath + "/namesPartByColor.parquet")

        // It is possible to use both partitioning and bucketing for a single table:
        usersDF.write.partitionBy("favorite_color")
            .bucketBy(42, "name").saveAsTable("users_partitioned_bucketed")

        /*
        partitionBy creates a directory structure as described in the Partition Discovery section.
        Thus, it has limited applicability to columns with high cardinality.
        In contrast bucketBy distributes data across a fixed number of buckets
        and can be used when the number of unique values is unbounded.
         */
        // ETL 经过抽取（extract）、转换（transform）、加载（load）
    }

    def genericFileDataResourceOption(): Unit ={
        // options | configurations: parquet, orc, avro, json, csv, tex
        // dir1/file3.json is corrupt from parquet's view
        spark.sql("set spark.sql.files.ignoreCorruptFiles=true")
        // after Construct the Dataframe, read the missing file
        spark.sql("set spark.sql.files.ignoreMissingFiles=true")

        val testCorruptDF = spark.read.parquet(
            HadoopUtils.sparkHome + "examples/src/main/resources/dir1/",
            HadoopUtils.sparkHome + "examples/src/main/resources/dir1/dir2/")
        testCorruptDF.show()

        //  The syntax follows org.apache.hadoop.fs.GlobFilter.
        //  It does not change the behavior of partition discovery
        val testGlobFilterDF = spark.read.format("parquet")
            .option("pathGlobFilter", "*.parquet") // json file should be filtered out
            .load(HadoopUtils.sparkHome + "examples/src/main/resources/dir1")
        testGlobFilterDF.show()

        // it disables partition inferring.
        // If data source explicitly specifies the partitionSpec when recursiveFileLookup is true,
        // exception will be thrown
        val recursiveLoadedDF = spark.read.format("parquet")
            .option("recursiveFileLookup", "true")
            .load(HadoopUtils.sparkHome + "examples/src/main/resources/dir1")
        recursiveLoadedDF.show()
    }

    // spark.stop()
}
