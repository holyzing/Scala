package com.holy.sparker

import org.apache.hadoop.security.UserGroupInformation
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.hbase.HBaseConfiguration
import org.apache.hadoop.hbase.client.HBaseAdmin
import org.apache.hadoop.hbase.mapreduce.TableInputFormat


// NOTE Spark 的经典应用：离线数仓, 实时数仓
// TODO Spark 读取 Aws S3
// TODO 和 裴磊要 BigExcavator 的源码

// 扩展：上层处理请求服务的扩展（RegionServer），下层存储数据的扩展（DataNode）

// 存储逻辑 (物理存储 和 逻辑存储)
//         Rowkey ColumnFamily ColumnQualifier TimeStamp Type Value

// 逻辑存储：按照行键组织形成一行，但是数据是以一个字典形式存储的，每行每个字典的键可以不一致
//         数据库操作的原子性在行，所有键值写完方为 写入完成。该种存储方式带来了 宽表和高表的概念
//         且 region的划分是根据 行健 进行划分的。
//         所以说行健的设计遵循的原则是 尽可能要散列在不同的 region Server 上，而且要包含一定的
//         业务信息。

//         HBase定义表时只需要声明列族即可，数据属性，比如超时时间（TTL），压缩算法（COMPRESSION）等，
//         都在列族的定义中定义，不需要声明具体的列。这意味着，往HBase写入数据时，字段可以动态、按需指定。
//         因此，和关系型数据库相比，HBase能够轻松应对字段变更的场景。

// 访问逻辑（如何定位查询）是如何执行查询，过滤的

// HReginServer 的个数是根据存储数据的大小来扩展的 ？？？
// Hmaster 启动的节点就在 Zookeeper所在的集群中 ？？？
// 一个列族中是如何按行组织数据的，不同列族之间呢 ？？？



// ---------------------------------------------------------------------------------------------------------------------
// Rowkey:
//   Bigtable通过行关键字的字典顺序来组织数据

// ColimnFamily：
//   “列族“，列族是访问控制的基本单位。存放在同一列族下的所有数据通常都属于同一个类型（我们可以把同一个列族下的数据压缩在一起）
//   访问控制、磁盘和内存的使用统计都是在列族层面进行的，控制权限能帮助我们管理不同类型的应用：
//      我们允许一些应用可以添加新的基本数据、
//      一些应用可以读取基本数据并创建继承的列族、
//      一些应用则只允许浏览数据（甚至可能因为隐私的原因不能浏览所有数据）
//      列蔟中的列是可以动态增加的。

// Timestamp：
//   "数据项" 中，不同版本的数据按照时间戳倒序排序，即最新的数据排在最前面。
//   为了减轻多个版本数据的管理负担，我们对每一个列族配有两个设置参数，Bigtable通过这两个参数可以对废弃版本的数据自动进行垃圾收集。
//   用户可以指定只保存最后n个版本的数据，或者只保存“足够新”的版本的数据（比如，只保存最近7天的内容写入的数据）

// Transaction：
//    Bigtable支持单行上的事务处理，利用这个功能，用户可以对存储在一个行关键字下的数据进行原子性的读-更新-写操作。
//    虽然Bigtable提供了一个允许用户跨行批量写入数据的接口，但是，Bigtable目前还不支持通用的跨行事务处理。
//    其次，Bigtable允许把数据项用做整数计数器。

// THINK BigTable依赖集群管理系统来调度任务、管理共享的机器上的资源、处理机器的故障、以及监视机器的状态 ？


// ---------------------------------------------------------------------------------------------------------------------
// Zookeeper：
//   高可用的、序列化的分布式锁服务组件 (Chubby 使用 Paxos 算法来保证副本的一致性)
//   保证集群中只有 1 个 master 在运行，如果 master 异常，会通过竞争机制产生新的 master 提供服务
//   监控 RegionServer 的状态，当 RegionSevrer 有异常的时候，通过回调的形式通知 Master RegionServer 上下线的信息
//   通过 Zoopkeeper 存储元数据的统一入口地址以及集群配置的维护等工作

// HMaster：
//   通过 Zookeeper 发布自己的位置给客户端
//   为 RegionServer 分配 Region，监控 RegionServer 处理 RegionServer 故障转移
//   维护整个集群的负载均衡，在空闲时间进行数据的负载均衡
//   维护集群的元数据信息，处理元数据的变更
//   处理 region 的分配或转移，发现失效的 Region，并将失效的 Region 分配到正常的 RegionServer 上
//   当 RegionSever 失效的时候，协调对应 Hlog 的拆分

//   和很多Single-Master类型的分布式存储系统类似，客户端读取的数据都不经过Master服务器：
//   客户程序直接和Tablet服务器通信进行读写操作。由于BigTable的客户程序不必通过Master服务器来获取Tablet的位置信息，
//   因此，大多数客户程序甚至完全不需要和Master服务器通信。在实际应用中，Master服务器的负载是很轻的

// HRegionServer：
//   负责存储 HBase 的实际数据，直接对接用户的读写请求，是真正的“干活”的节点。它的功能概括如下：
//   管理 master 为其分配的 Region
//   处理来自客户端的读写请求
//   负责和底层 HDFS 的交互，存储数据到 HDFS，刷新缓存到 HDFS
//   负责 Region 变大以后的拆分，负责处理 Region 分片
//   负责 Storefile 的合并工作
//   维护 Hlog，执行压缩

// Hdfs：
//   HDFS 为 Hbase 提供最终的底层数据存储服务，同时为 HBase 提供高可用（Hlog 存储在HDFS）的支持
//   提供元数据和表数据的底层分布式存储服务

// Write-Ahead logs：
//   HBase 的修改记录，当对 HBase 读写数据的时候，数据不是直接写进磁盘，
//   它会在内存中保留一段时间（时间以及数据量阈值可以设定）。但把数据保存
//   在内存中可能有更高的概率引起数据丢失，为了解决这个问题，数据会先写在
//   一个叫做 Write-Ahead logfile 的文件中，然后再写入内存中。所以在系
//   统出现故障的时候，数据可以通过这个日志文件重建。

// Region：
//   Hbase 表的分片，HBase 表会根据 RowKey值被切分成不同的 region 存储在 RegionServer中，
//   在一个 RegionServer 中可以有多个不同的 region。

//   Bigtable 使用一个三层的、类似Ｂ+树[10]的结构存储Tablet的位置信息




// Store：
//    HFile 存储在 Store 中，一个 Store 对应 HBase 表中的一个列族。

// MemStore：
//    顾名思义，就是内存存储，位于内存中，用来保存当前的数据操作，所以当数据保存在
//    WAL 中之后，RegsionServer 会在内存中存储键值对。 尚硅谷大数据技术之 HBase

// HFile：
// 这是在磁盘上保存原始数据的实际的物理文件，是实际的存储文件。StoreFile 是以 Hfile的形式存储在 HDFS 的。

// BigTable内部存储数据的文件是Google SSTable格式的。SSTable是一个持久化的、排序的、不可更改的Map结构，
// 而Map是一个key-value映射的数据结构，key和value的值都是任意的Byte串。可以对SSTable进行如下的操作：
// 查询与一个key值相关的value，或者遍历某个key值范围内的所有的key-value对。从内部看，SSTable是一系列的数据块
// （通常每个块的大小是64KB，这个大小是可以配置的）。SSTable使用块索引（通常存储在SSTable的最后）来定位数据块；
// 在打开SSTable的时候，索引被加载到内存。每次查找都可以通过一次磁盘搜索完成：首先使用二分查找法在内存中的索引里找
// 到数据块的位置，然后再从硬盘读取相应的数据块。也可以选择把整个SSTable都放在内存中，这样就不必访问硬盘了。



object HadoopUtils {
    val os: String = System.getProperty("os.name")
    var workHome: String = _
    var sparkHome: String = _
    var keytabPath: String = _
    var krb5ConfPath: String = _
    val username = "houleilei"
    // IDE 推荐使用 _ 而不是 null, scala 中 的 _ 代表什么 ? println(_) 会报错

    if (os != null && os.toLowerCase().indexOf("linux")> -1){
        krb5ConfPath = "/etc/krb5.conf"
        workHome = "/home/holyzing/xtalpi/My/_03_Scala/Scala/xtalpi"
        keytabPath = workHome + "/src/resources/houleilei.client.keytab"
        sparkHome = "/home/holyzing/snap/apache/spark-3.0.1-bin-hadoop2.7"
    } else {
        workHome = "F:/mywork/Scala/xtalpi"
        sparkHome = "F:/apache/spark-3.0.1-bin-hadoop2.7"
        krb5ConfPath = workHome + "/src/resources/krb5.conf"
        keytabPath = workHome + "/src/resources/houleilei.client.keytab"
    }
    sparkHome = sys.env.getOrElse("SPARK_HOME", sparkHome)  // windows 下是生效的
    sparkHome = sparkHome.replace("\\", "/")

    val hdfsHome = "hdfs://hadoop01.stor:8020/home/holyzing/"

    def setProperties(): Unit ={
        System.setProperty("user.name", username)
        System.setProperty("HADOOP_USER_NAME", username)
        System.setProperty("java.security.krb5.conf", krb5ConfPath)
    }

    def hadoopConfig(config: Configuration): Unit = {
        // val hadoop1Home = sys.env.get("TEST")
        // val hadoop2Home = System.getenv("TEST")
        // sys.env.toSet("LD_LIBRARY_PATH", hadoop2Home + "/lib/native/")
        // System.setProperty("LD_LIBRARY_PATH", hadoop1Home + "/lib/native/")
        // System.setProperty("HADOOP_CONF_DIR", "/home/holyzing/snap/apache/hadoop-2.7.7/etc/hadoop/")
        // System.setProperty("HADOOP_CLASSPATH", "/home/holyzing/snap/apache/hadoop-2.7.7/etc/hadoop/")

        // val classLoader = Thread.currentThread.getContextClassLoader
        // val urlclassLoader = classLoader.asInstanceOf[URLClassLoader]
        // val urls = urlclassLoader.getURLs
        // for (url <- urls) {
        //     if (url.getPath.contains("target")){
        //         println("------------------>" + url)
        //     }
        // }

        // http://hadoop01.stor:50070   // hdfs://10.111.32.184:8020
        // RULE:[2:$1@$0]([nd]n@.*XTALPI-BJ.COM)s/.*/hadoop/
        // DEFAULT
        // dp/admin@GAI.COM

        setProperties()
        // kerberos集群配置文件配置
        config.set("hadoop.security.authentication", "kerberos")

        // config.set("dfs.namenode.kerberos.principal.pattern", "*/*@XTALPI-BJ.COM")
        // config.hadoopConfiguration.set("dfs.namenode.kerberos.principal", "nn/_HOST@XTALPI-BJ.COM")

        // NOTE Configuration 静态代码块会主动加载 classpath 下的配置文件
        // config.addResource("tmp/core-site.xml")
        // config.addResource("tmp/hdfs-site.xml")
        // config.addResource("tmp/yarn-site.xml")

        // config.addResource("modules/LogProcess/src/res/core-site.xml")
        // config.addResource("modules/LogProcess/src/res/hdfs-site.xml")
        // config.addResource("modules/LogProcess/src/res/yarn-site.xml")
        // config.addResource("modules/LogProcess/src/res/hadoop-policy.xml")
        // config.addResource("modules/LogProcess/src/res/kms-acls.xml")
        // config.addResource("modules/LogProcess/src/res/mapred-site.xml")
        // config.addResource("modules/LogProcess/src/res/ssl-client.xml")
        // config.addResource("modules/LogProcess/src/res/ssl-server.xml")
        // config.set("fs.defaultFS","hdfs://10.111.32.184:8020")

        UserGroupInformation.setConfiguration(config)
        UserGroupInformation.loginUserFromKeytab(username, keytabPath)

        // 50070 web 管理端口  8020 rpc调用
    }

    def hbaseConfig(inputTable: String, outputTable: String): Configuration ={
        val conf = HBaseConfiguration.create()
        conf.set("hbase.zookeeper.quorum", "hbase01.stor:2181,hbase02.stor:2181,hbase03.stor:2181")

        // conf.set("hbase.zookeeper.property.clientPort","2181")
        // TODO 当前classpath 不存在该类
        // conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
        // 这个配置的作用就是：一个ip所对应的客户机，只能和zk服务器维持60个连接。
        // conf.set("hbase.zookeeper.property.maxClientCnxns", "60");
        // conf.addResource("hbase-site.xml")
        // !_.equals('$')

        if (inputTable != null){
            conf.set(TableInputFormat.INPUT_TABLE, inputTable) // "houleilei:jobinfos"
        }
        if (outputTable != null){
            conf.set("hbase.mapreduce.hfileoutputformat.table.name", outputTable)
            conf.setInt("hbase.mapreduce.bulkload.max.hfiles.perRegion.perFamily", 5000)
        }

        try {
            hadoopConfig(conf)
            HBaseAdmin.checkHBaseAvailable(conf)
        } catch {
            case ex: Exception =>
                println(ex)
                return null
        }

        conf
    }

}
